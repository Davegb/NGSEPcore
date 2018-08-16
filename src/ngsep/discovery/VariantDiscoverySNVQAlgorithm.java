package ngsep.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import ngsep.math.PhredScoreHelper;
import ngsep.sequences.DNASequence;
import ngsep.sequences.HammingSequenceDistanceMeasure;
import ngsep.sequences.SequenceDistanceMeasure;
import ngsep.variants.CalledGenomicVariant;
import ngsep.variants.CalledGenomicVariantImpl;
import ngsep.variants.CalledSNV;
import ngsep.variants.GenomicVariant;
import ngsep.variants.GenomicVariantImpl;
import ngsep.variants.SNV;
import ngsep.variants.VariantCallReport;

public class VariantDiscoverySNVQAlgorithm {

	private static int posPrint = -1;
	private static SequenceDistanceMeasure distanceMeasure = new HammingSequenceDistanceMeasure();
	
	public static CountsHelper calculateCountsSNV (PileupRecord pileup, short maxBaseQS, Set<String> readGroups) {
		CountsHelper answer = new CountsHelper();
		if(maxBaseQS>0) answer.setMaxBaseQS(maxBaseQS);
		List<PileupAlleleCall> calls = pileup.getAlleleCalls(1);
		for(PileupAlleleCall call:calls ) {
			if(readGroups!=null && !readGroups.contains(call.getReadGroup())) continue;
			short q = (short)(call.getQualityScores().charAt(0)-33);
			answer.updateCounts(call.getSequence().subSequence(0,1).toString(), q, (short)255);
		}
		return answer;
	}

	//PRE: Reference base is uppercase
	public static CalledGenomicVariant callSNV(PileupRecord pileup, CountsHelper countsHelper, GenomicVariant variant, char refBase, double heterozygosityRate) {
		CalledGenomicVariant newCall;
		if(variant!=null) {
			newCall = genotypeSNV(countsHelper, variant, heterozygosityRate);
		} else {
			if(countsHelper.getTotalCount()==0) {
				return null;
			}
			if(DNASequence.BASES_STRING.indexOf(refBase)<0) {
				//N reference can in principle be handled but it generates  many non variant sites
				return null;
			}
			newCall = findNewSNV(pileup,countsHelper,refBase,heterozygosityRate);
		}
		return newCall;
	}
	//PRE: variant!=null
	private static CalledGenomicVariant genotypeSNV(CountsHelper countsHelper, GenomicVariant variant, double heterozygosityRate) {
		CalledGenomicVariant newCall;
		if(countsHelper.getTotalCount()==0) {
			CalledGenomicVariantImpl undecidedCall = new CalledGenomicVariantImpl(variant, new byte [0]);
			undecidedCall.setAllCounts(countsHelper.getCounts());
			return undecidedCall;
		}
		int [] allCounts = countsHelper.getCounts();
		double [] [] allLogConditionals = countsHelper.getLogConditionalProbs();
		double [][] postProbs = countsHelper.getPosteriorProbabilities(heterozygosityRate);
		
		
		if(variant instanceof SNV) {
			SNV snv = (SNV)variant;
			byte indexRef = snv.getRefBaseDNAIndex();
			byte indexAlt = snv.getAltBaseDNAIndex();
			double pHomoRef = postProbs[indexRef][indexRef];
			double pMax = pHomoRef;
			byte genotype = CalledGenomicVariant.GENOTYPE_HOMOREF;
			double pHomoAlt = postProbs[indexAlt][indexAlt];
			if(pHomoAlt>pMax+0.01) {
				pMax = pHomoAlt;
				genotype = CalledGenomicVariant.GENOTYPE_HOMOALT;
			}
			double pHetero = postProbs[indexRef][indexAlt]+postProbs[indexAlt][indexRef];
			if(pHetero>pMax+0.01) {
				pMax = pHetero;
				genotype = CalledGenomicVariant.GENOTYPE_HETERO;
			}
			short gq = PhredScoreHelper.calculatePhredScore(1-pMax);
			if(gq==0) genotype = CalledGenomicVariant.GENOTYPE_UNDECIDED;
			CalledSNV csnv = new CalledSNV((SNV) variant, genotype);
			csnv.setGenotypeQuality(gq);
			csnv.setTotalReadDepth(countsHelper.getTotalCount());
			csnv.setAllBaseCounts(allCounts);
			csnv.setAllGenotypeLogConditionals(allLogConditionals);
			newCall = csnv;
		} else {
			String [] alleles = variant.getAlleles();
			int [] indexes = new int [alleles.length];
			for(int i=0;i<alleles.length;i++) {
				indexes[i] = DNASequence.BASES_STRING.indexOf(alleles[i].charAt(0));
			}
			int [] reportCounts = makeReportCounts(allCounts, indexes);
			double [][] reportLogs = makeReportProbs(allLogConditionals, indexes);
			double [][] reportPosteriors = makeReportProbs(postProbs, indexes);
			int [] indexesMax = getIndexesMaxGenotype(reportPosteriors, 0);
			double maxP = reportPosteriors[indexesMax[0]][indexesMax[1]];
			byte [] genotype;
			if(indexesMax[0]!=indexesMax[1]) {
				maxP+= reportPosteriors[indexesMax[1]][indexesMax[0]];
				genotype = new byte[2];
				genotype[0] = (byte) indexesMax[0];
				genotype[1] = (byte) indexesMax[1];
			} else {
				genotype = new byte[1];
				genotype[0] = (byte) indexesMax[0];
			}
			short gq = PhredScoreHelper.calculatePhredScore(1-maxP);
			//Triallelic variant 
			CalledGenomicVariantImpl call = new CalledGenomicVariantImpl(variant, genotype);
			call.setGenotypeQuality(gq);
			call.setTotalReadDepth(countsHelper.getTotalCount());
			call.setAllCounts(allCounts);
			VariantCallReport report = new VariantCallReport(alleles, reportCounts, reportLogs);
			call.setCallReport(report);
			newCall = call;
		}
		return newCall;
	}
	//Calls the SNVQ algorithm from the counts stored in the counts helper object
	private static CalledGenomicVariant findNewSNV(PileupRecord pileup, CountsHelper countsHelper, char refBase, double heterozygosityRate) {
		String bases = DNASequence.BASES_STRING;
		byte indexRef = (byte) bases.indexOf(refBase);
		int [] counts = countsHelper.getCounts();
		double [][] postProbs = countsHelper.getPosteriorProbabilities(heterozygosityRate);
		if(pileup.getPosition()==posPrint) {
			System.out.println("Posteriors");
			countsHelper.printProbs(postProbs, false);
		}
		int [] indexesMax = getIndexesMaxGenotype(postProbs, indexRef);
		double refProb = 0;
		int indexI=indexesMax[0],indexJ=indexesMax[1];
		if(indexRef>=0) {
			refProb = postProbs[indexRef][indexRef];
		}
		double maxP = postProbs[indexI][indexJ];
		if(indexI!=indexJ) maxP += postProbs[indexJ][indexI];
		short gq = PhredScoreHelper.calculatePhredScore(1-maxP);
		if(pileup.getPosition()==posPrint) System.out.println("Max probability: "+maxP+". IndexI: "+indexI+" indexJ: "+indexJ); 
		int indexAlt=0;
		//Solve first non SNV case
		if(indexRef<0 || (indexI!=indexJ && indexI!=indexRef && indexJ!=indexRef)) {
			//Triallelic or weird reference variant
			int indexThird=-1;
			int nAlleles = 2;
			if(indexI!=indexJ) {
				if(postProbs[indexI][indexI]>postProbs[indexJ][indexJ]+0.01) {
					indexAlt = indexI;
					indexThird = indexJ;
				} else {
					indexAlt = indexJ;
					indexThird = indexI;
				}
				nAlleles = 3;
			} else {
				indexAlt = indexI;
			}
			
			List<String> alleles = new ArrayList<String>();
			byte [] idsCalledAlleles = new byte[nAlleles-1];
			idsCalledAlleles[0] = 1;
			int [] indexes = new int [nAlleles];
			indexes[0] = indexRef;
			indexes[1] = indexAlt;
			if(indexRef>=0) {
				alleles.add(DNASequence.BASES_ARRAY[indexRef]);
			} else {
				alleles.add(""+refBase);
			}
			alleles.add(DNASequence.BASES_ARRAY[indexAlt]);
			if(indexThird>=0) {
				alleles.add(DNASequence.BASES_ARRAY[indexThird]);
				idsCalledAlleles[1] = 2;
				indexes[2] = indexThird;
			}
			int[] reportCounts = makeReportCounts(counts, indexes);
			GenomicVariantImpl gv = new GenomicVariantImpl(pileup.getSequenceName(), pileup.getPosition(), alleles);
			gv.setType(GenomicVariant.TYPE_MULTIALLELIC_SNV);
			gv.setVariantQS(PhredScoreHelper.calculatePhredScore(refProb));
			CalledGenomicVariantImpl triallelicVar = new CalledGenomicVariantImpl(gv, idsCalledAlleles);
			triallelicVar.setGenotypeQuality(gq);
			triallelicVar.setTotalReadDepth(countsHelper.getTotalCount());
			triallelicVar.setAllCounts(counts);
			double [] [] reportLogConds = makeReportProbs(countsHelper.getLogConditionalProbs(),indexes);
			triallelicVar.setCallReport(new VariantCallReport(alleles.toArray(new String [0]), reportCounts, reportLogConds));
			return triallelicVar;
		}
		char altBase=0;
		byte genotype=0;
		
		if (indexI!=indexJ) {
			if(indexRef!=indexI ) {
				indexAlt = indexI;
			} else {
				indexAlt = indexJ;
			}
			altBase = bases.charAt(indexAlt);
			genotype = 1;
		} else if(indexRef!=indexI ) {
			//Homozygous non reference
			indexAlt = indexI;
			altBase = bases.charAt(indexAlt);
			genotype = 2;
		} else {
			//Homozygous reference only useful for genotypeAll mode
			List<String> alleles = new ArrayList<String>();
			alleles.add(DNASequence.BASES_ARRAY[indexRef]);
			GenomicVariantImpl gvNoVar = new GenomicVariantImpl(pileup.getSequenceName(), pileup.getPosition(), alleles);
			gvNoVar.setVariantQS(PhredScoreHelper.calculatePhredScore(refProb));
			byte [] idsCalledAlleles = {0};
			int [] indexes = {indexRef};
			CalledGenomicVariantImpl noVarCall = new CalledGenomicVariantImpl(gvNoVar, idsCalledAlleles);
			noVarCall.setGenotypeQuality(gq);
			noVarCall.setTotalReadDepth(countsHelper.getTotalCount());
			noVarCall.setAllCounts(counts);
			int[] reportCounts = makeReportCounts(counts, indexes);
			double [] [] reportLogConds = makeReportProbs(countsHelper.getLogConditionalProbs(),indexes);
			noVarCall.setCallReport(new VariantCallReport(alleles.toArray(new String [0]), reportCounts, reportLogConds));
			return noVarCall;
		}
		SNV snv = new SNV(pileup.getSequenceName(), pileup.getPosition(), refBase, altBase);
		snv.setVariantQS(PhredScoreHelper.calculatePhredScore(refProb));
		CalledSNV csnv = new CalledSNV(snv,genotype);
		csnv.setGenotypeQuality(gq);
		csnv.setTotalReadDepth(countsHelper.getTotalCount());
		csnv.setAllBaseCounts(counts);
		csnv.setAllGenotypeLogConditionals(countsHelper.getLogConditionalProbs());
		return csnv;
	}
	private static int [] getIndexesMaxGenotype (double [][] genotypePosteriors, int indexDefault) {
		if(indexDefault<0 || indexDefault>=genotypePosteriors.length) {
			indexDefault=0;
		}
		int [] indexes = {indexDefault,indexDefault};
		double probMax = genotypePosteriors[indexDefault][indexDefault];
		for(int i=0;i<genotypePosteriors.length;i++) {
			for(int j=i;j<genotypePosteriors[0].length;j++) {
				//For heterozygous genotypes add up the two events corresponding with the two ways to sort the alleles
				double genotypeProbability = genotypePosteriors[i][j];
				if(i!=j) genotypeProbability += genotypePosteriors[j][i];
				//Differences of less than 0.01 are considered equal
				if (genotypeProbability > probMax+0.01) {
					probMax = genotypeProbability;
					indexes[0] = i;
					indexes[1] = j;
				}
			}
		}
		return indexes;
	}
	private static int[] makeReportCounts(int[] counts, int [] indexes) {
		int [] reportCounts = new int [indexes.length];
		for(int i=0;i<indexes.length;i++) {
			if(indexes[i] <0 || indexes[i] >= counts.length) reportCounts[i] = 0;
			else reportCounts[i] = counts[indexes[i]];
		}
		return reportCounts;
	}
	private static double[][] makeReportProbs(double[][] allValues, int [] indexes) {
		double [][] answer = new double[indexes.length][indexes.length];
		for(int i=0;i<indexes.length;i++) {
			for(int j=0;j<indexes.length;j++) {
				if(indexes[i] <0 || indexes[i] >= allValues.length) answer[i][j] = 0;
				else if(indexes[j] <0 || indexes[j] >= allValues[0].length) answer[i][j] = 0;
				else answer[i][j] = allValues[indexes[i]][indexes[j]];
			}
		}
		return answer;
	}

	//PRE: Reference allele is not null and its length is larger than 1. If variant is not null, reference allele is the reference allele of the variant
	public static CountsHelper calculateCountsIndel(PileupRecord pileup, GenomicVariant variant, String referenceAllele, Set<String> readGroups) {
		//if(pileup.getPosition()==posPrint) System.out.println("Processing calls at: "+posPrint+" Reference: "+referenceAllele);
		String [] indelAlleles;
		List<PileupAlleleCall> calls = pileup.getAlleleCalls(referenceAllele.length());
		AlleleCallClustersBuilder acBuilder = new AlleleCallClustersBuilder();
		for(PileupAlleleCall call:calls) {
			if(readGroups!=null && !readGroups.contains(call.getReadGroup())) continue;
			String allele = call.getSequence().toString();
			//if(pileup.getPosition()==posPrint) System.out.println("Adding to cluster allele call: "+allele.toUpperCase());
			acBuilder.addAlleleCall(allele.toUpperCase());	
		}
		Map<String, List<String>> alleleClusters;
		if(variant!=null) {
			indelAlleles = variant.getAlleles();
			alleleClusters = acBuilder.clusterAlleleCalls(indelAlleles,false);
		} else {
			String [] suggestedAlleles = new String[1];
			suggestedAlleles[0] = referenceAllele;
			alleleClusters = acBuilder.clusterAlleleCalls(suggestedAlleles,true);
			if(alleleClusters.size()>100) System.err.println("WARN: Number of alleles for site at "+pileup.getSequenceName()+":"+pileup.getPosition()+" is "+alleleClusters.size()+" ref Allele: "+referenceAllele);
			Set<String> indelAllelesSet = new TreeSet<>(alleleClusters.keySet());
			indelAllelesSet.add(referenceAllele);
			indelAlleles = new String [indelAllelesSet.size()];
			indelAlleles[0] = referenceAllele;
			//if(pileup.getPosition()==posPrint) System.out.println("Reference allele for indel: "+referenceAllele);
			int i=1;
			for (String allele:indelAllelesSet) {
				if(!allele.equals(referenceAllele)) {
					indelAlleles[i] = allele;
					//if(pileup.getPosition()==posPrint) System.out.println("Next alternative allele for indel: "+allele);
					i++;
				}
			}
		}
		CountsHelper answer = new CountsHelper(indelAlleles);
		//This should apply only to real base quality scores
		//if(maxBaseQS>0) answer.setMaxBaseQS(maxBaseQS);
		for(String allele:alleleClusters.keySet()) {
			List<String> cluster = alleleClusters.get(allele);
			//if(pileup.getPosition()==posPrint) System.out.println("Next allele: "+allele+" count: "+cluster.size());
			for(String call:cluster) {
				double p = distanceMeasure.calculateNormalizedDistance(allele, call)+0.01;
				p = p*p;
				short q = PhredScoreHelper.calculatePhredScore(p);
				//if(pileup.getPosition()==posPrint) System.out.println("Next call to count: "+call+" quality: "+q);
				answer.updateCounts(allele, q, (short)255);
			}
		}
		/*for(int i=0;i<callsWithScores.size();i+=2) {
			String call = callsWithScores.get(i);
			String scores = callsWithScores.get(i+1);
			if(pileup.getPosition()==posPrint) System.out.println("Next call for indel: "+call+" scores: "+scores);
			answer.updateCounts(call, '5', (short)255);
		}*/
		return answer;
	}
	
	public static CalledGenomicVariant callIndel (PileupRecord pileup, CountsHelper helper, GenomicVariant variant, double heterozygosityRate) {
		int [] counts = helper.getCounts();
		double [][] postProbs = helper.getPosteriorProbabilities(heterozygosityRate);
		if(pileup.getPosition()==posPrint) {
			System.out.println("Counts");
			for(int j=0;j<counts.length;j++) System.out.println("Count allele: "+helper.getAlleles()[j]+": "+counts[j]); 
			System.out.println("Probs");
			helper.printProbs(postProbs, false);
		}
		if(helper.getTotalCount()==0) {
			if(variant == null) return null;
			return new CalledGenomicVariantImpl(variant, new byte [0]);
		}
		int [] indexesMax = getIndexesMaxGenotype(postProbs, 0);
		if(pileup.getPosition()==posPrint) System.out.println("Indexes max: "+indexesMax[0]+" "+indexesMax[1]);
		byte [] calledAlleles;
		GenomicVariant gv = variant;
		int[] reportCounts = counts;
		double [][] reportLogs = helper.getLogConditionalProbs();
		if(gv == null) {
			String [] helperAlleles = helper.getAlleles();
			List<String> alleles = new ArrayList<String>();
			List<Integer> indexesList = new ArrayList<Integer>();
			//Add reference allele
			alleles.add(helperAlleles[0]);
			int referenceLength = helperAlleles[0].length();
			indexesList.add(0);
			boolean lengthChange = false;
			if(indexesMax[0] > 0 && indexesMax[0] < helperAlleles.length) {
				String allele0 = helperAlleles[indexesMax[0]]; 
				alleles.add(allele0);
				indexesList.add(indexesMax[0]);
				if(allele0.length()!=referenceLength) lengthChange = true;
			}
			if(indexesMax[1] > 0 && indexesMax[1] !=indexesMax [0] && indexesMax[1]<helperAlleles.length ) {
				String allele1 = helperAlleles[indexesMax[1]];
				alleles.add(allele1);
				indexesList.add(indexesMax[1]);
				if(allele1.length()!=referenceLength) lengthChange = true;
				if(alleles.size()==3 && allele1.length()!=alleles.get(1).length()) lengthChange = true;
			}
			//No real indel if all alleles have the same length
			if(!lengthChange && !pileup.isInputSTR()) return null;
			GenomicVariantImpl newVar = new GenomicVariantImpl(pileup.getSequenceName(), pileup.getPosition(), alleles);
			if(pileup.isSTR()) newVar.setType(GenomicVariant.TYPE_STR);
			else newVar.setType(GenomicVariant.TYPE_INDEL);
			newVar.setVariantQS(PhredScoreHelper.calculatePhredScore(postProbs[0][0]));
			gv = newVar;
			int [] indexes = new int [indexesList.size()];
			for(int i=0;i<indexes.length;i++) indexes[i] = indexesList.get(i);
			reportCounts = makeReportCounts(counts, indexes);
			reportLogs = makeReportProbs(helper.getLogConditionalProbs(), indexes);
			if(indexesMax[1] !=indexesMax [0]) {
				calledAlleles = new byte[2];
				if (alleles.size()==3) {
					calledAlleles[0] = 1;
					calledAlleles[1] = 2;
				} else  {
					calledAlleles[0] = 0;
					calledAlleles[1] = 1;
				}
			} else {
				calledAlleles = new byte[1];
				if(indexesMax[0] == 0) {
					calledAlleles[0] = 0;
				} else {
					calledAlleles[0] = 1;
				}
			}
		} else {
			if (indexesMax[0] > GenomicVariant.MAX_NUM_ALLELES || indexesMax[1]>GenomicVariant.MAX_NUM_ALLELES) {
				calledAlleles = new byte[0];
			} else if(indexesMax[1] !=indexesMax [0]) {
				calledAlleles = new byte[2];
				calledAlleles[0] = (byte) indexesMax[0];
				calledAlleles[1] = (byte) indexesMax[1];
			} else {
				calledAlleles = new byte[1];
				calledAlleles[0] = (byte) indexesMax[0];
			}
		}
		
		int totalDepth = helper.getTotalCount();
		VariantCallReport report = new VariantCallReport(gv.getAlleles(), reportCounts, reportLogs);
		CalledGenomicVariantImpl newCall = new CalledGenomicVariantImpl(gv, calledAlleles);
		double maxP = postProbs[indexesMax[0]][indexesMax[1]];
		if(indexesMax[0]!=indexesMax[1]) maxP+= postProbs[indexesMax[1]][indexesMax[0]];
		newCall.setGenotypeQuality(PhredScoreHelper.calculatePhredScore(1-maxP));
		newCall.setTotalReadDepth(totalDepth);
		if(totalDepth>0) newCall.setCallReport(report);
		//if(pileup.getFirst()==82) System.out.println("Indel alleles: "+newCall.getAlleles().length+" called alleles: "+calledAlleles[0]+" "+calledAlleles[1]+" genotype prob: "+newCall.getGenotypeProbability());
		return newCall;
	}
}