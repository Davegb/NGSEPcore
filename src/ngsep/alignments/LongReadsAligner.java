/*******************************************************************************
 * NGSEP - Next Generation Sequencing Experience Platform
 * Copyright 2016 Jorge Duitama
 *
 * This file is part of NGSEP.
 *
 *     NGSEP is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     NGSEP is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with NGSEP.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package ngsep.alignments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ngsep.assembly.AlignmentConstantGap;
import ngsep.assembly.GraphBuilderFMIndex;
import ngsep.genome.GenomicRegionSpanComparator;
import ngsep.sequences.FMIndexUngappedSearchHit;
import ngsep.sequences.KmerHitsCluster;
import ngsep.sequences.KmersExtractor;
import ngsep.sequences.PairwiseAlignmentAffineGap;
import ngsep.sequences.RawRead;

/**
 * @author Jorge Duitama
 */
public class LongReadsAligner {

	private int maxLengthFullPairwiseAlignment = 2000;
	private PairwiseAlignmentAffineGap aligner = new PairwiseAlignmentAffineGap(1, 2, 1, 1);
	public Map<CharSequence, Integer> extractUniqueKmers(CharSequence sequence, int start, int end) {
		Map<Integer, CharSequence> rawKmers = KmersExtractor.extractKmersAsMap(sequence, 15, 1, start, end, true, true, true);
		Map<CharSequence, Integer> answer = new LinkedHashMap<CharSequence, Integer>();
		Map<CharSequence, Integer> reverseMap = new HashMap<CharSequence,Integer>();
		Set<Integer> multiple = new HashSet<>();
		for(int i=start;i<end;i++) {
			CharSequence kmer = rawKmers.get(i);
			if(kmer == null) {
				multiple.add(i);
				continue;
			}
			Integer previousStart = reverseMap.get(kmer);
			if(previousStart!=null) {
				multiple.add(i);
				continue;
			}
			reverseMap.put(kmer,i);
		}
		for(int i=start;i<end;i++) {
			CharSequence kmer = rawKmers.get(i);
			if(kmer!=null && !multiple.contains(i)) {
				answer.put(kmer,i);
			}
			
		}
		return answer;
	}

	public ReadAlignment alignRead(CharSequence subject, CharSequence read, int start, int end, String subjectName) {
		Map<CharSequence, Integer> uniqueKmersSubject = extractUniqueKmers(subject,start,end);
		//System.out.println("Number of unique k-mers subject: "+uniqueKmersSubject.size());
		return alignRead(subject, read, uniqueKmersSubject, subjectName);
	}
	public ReadAlignment alignRead(CharSequence subject, CharSequence read, Map<CharSequence, Integer> uniqueKmersSubject, String subjectName) {
		Map<CharSequence, Integer> uniqueKmersRead = extractUniqueKmers(read,0,read.length());
		//System.out.println("Number of unique k-mers read: "+uniqueKmersRead.size());
		List<FMIndexUngappedSearchHit> initialKmerHits = new ArrayList<FMIndexUngappedSearchHit>();
		for(CharSequence kmerRead:uniqueKmersRead.keySet()) {
			Integer subjectPos = uniqueKmersSubject.get(kmerRead);
			if(subjectPos==null) continue;
			FMIndexUngappedSearchHit hit = new FMIndexUngappedSearchHit(kmerRead.toString(), "", subjectPos);
			hit.setQueryIdx(uniqueKmersRead.get(kmerRead));
			initialKmerHits.add(hit);
		}
		if(initialKmerHits.size()==0) return null;
		List<KmerHitsCluster> clusters = GraphBuilderFMIndex.clusterSequenceKmerAlns(0, read, initialKmerHits);
		Collections.sort(clusters, (o1,o2)->o2.getNumDifferentKmers()-o1.getNumDifferentKmers());
		//printClusters(clusters);
		if(clusters.size()>1) {
			KmerHitsCluster c1 = clusters.get(0);
			KmerHitsCluster c2 = clusters.get(1);
			int overlap = GenomicRegionSpanComparator.getInstance().getSpanLength(c1.getFirst(), c1.getLast(), c2.getFirst(), c2.getLast());
			if((overlap <0.9*c1.length() || overlap < 0.9*c2.length()) && c1.getNumDifferentKmers()<0.9*initialKmerHits.size()) {
				return null;
			}	
		}
		KmerHitsCluster bestCluster = clusters.get(0);
		//System.out.println("Number of clusters: "+clusters.size()+" best cluster kmers: "+bestCluster.getNumDifferentKmers()+" first "+bestCluster.getFirst()+" last "+bestCluster.getLast());
		return buildCompleteAlignment(subject, read.toString(), bestCluster, subjectName);
	}
	public void printClusters(List<KmerHitsCluster> clusters) {
		System.out.println("Clusters: "+clusters.size());
		for(KmerHitsCluster cluster:clusters) {
			System.out.println("kmers: "+cluster.getNumDifferentKmers()+" first: "+cluster.getFirst()+" last: "+cluster.getLast()+" query limits "+cluster.getQueryStart()+"-"+cluster.getQueryEnd());
		}
		
	}

	private ReadAlignment buildCompleteAlignment(CharSequence subject, CharSequence query, KmerHitsCluster kmerHitsCluster, String subjectName) {
		List<FMIndexUngappedSearchHit> kmerHits = kmerHitsCluster.getHitsByQueryIdx();
		
		int clusterFirst = kmerHitsCluster.getFirst();
		int subjectNext = Math.max(0, clusterFirst-1);
		//System.out.println("Subject length: "+subject.length()+". Query length: "+query.length()+" kmer hits: "+kmerHits.size()+" subject next: "+subjectNext+ " cluster last "+kmerHitsCluster.getLast());
		int queryNext = 0;
		int alnStart = -1;
		char matchOp = ReadAlignment.ALIGNMENT_CHAR_CODES.charAt(ReadAlignment.ALIGNMENT_MATCH);
		char insertionOp = ReadAlignment.ALIGNMENT_CHAR_CODES.charAt(ReadAlignment.ALIGNMENT_INSERTION);
		char deletionOp = ReadAlignment.ALIGNMENT_CHAR_CODES.charAt(ReadAlignment.ALIGNMENT_DELETION);
		char softClipOp = ReadAlignment.ALIGNMENT_CHAR_CODES.charAt(ReadAlignment.ALIGNMENT_SKIPFROMREAD);
		StringBuilder cigar = new StringBuilder();
		int nextMatchLength = 0;
		for(FMIndexUngappedSearchHit kmerHit:kmerHits) {
			//System.out.println("Processing Kmer hit at pos: "+kmerHit.getQueryIdx()+" query next: "+queryNext+" subject next: "+subjectNext+" subject hit start: "+kmerHit.getStart()+" cigar length: "+cigar.length());
			int kmerLength = kmerHit.getQuery().length();
			if(alnStart==-1) {
				alnStart = kmerHit.getStart();
				if(kmerHit.getQueryIdx()>0) cigar.append(""+kmerHit.getQueryIdx()+""+softClipOp);
				nextMatchLength+=kmerLength;
				subjectNext = kmerHit.getStart()+kmerLength;
				queryNext = kmerHit.getQueryIdx()+kmerLength;
			} else if(kmerHit.getQueryIdx() >= queryNext && subjectNext<=kmerHit.getStart()) {
				//Kmer does not overlap with already aligned segments
				String subjectStr = subject.subSequence(subjectNext,kmerHit.getStart()).toString();
				String queryStr = query.subSequence(queryNext,kmerHit.getQueryIdx()).toString();
				if(subjectStr.length()==queryStr.length() && (subjectStr.length()<10 || subjectStr.length()>maxLengthFullPairwiseAlignment)) {
					nextMatchLength+=subjectStr.length();
				} else {
					if(nextMatchLength>0 && (subjectStr.length()>0 || queryStr.length()>0)) {
						//System.out.println("Found internal segment for possible alignment. Subject length "+subjectStr.length()+" query length "+queryStr.length()+" current match length: "+nextMatchLength);
						cigar.append(""+nextMatchLength+""+matchOp);
						nextMatchLength = 0;
					}
					if(subjectStr.length()>0 && queryStr.length()>0) {
						//if (subjectStr.length()>100 || queryStr.length()>100) System.out.println("Aligning segment of length "+subjectStr.length()+" of subject with total length: "+subject.length()+" to segment with length "+queryStr.length()+" of query with total length: "+query.length());
						if (subjectStr.length()>maxLengthFullPairwiseAlignment || queryStr.length()>maxLengthFullPairwiseAlignment) return null;
						String [] alignedFragments = aligner.getAlignment(subjectStr, queryStr);
						String cigarSegment = buildCigar(alignedFragments[0],alignedFragments[1]);
						cigar.append(cigarSegment);
						
					} else if (subjectStr.length()>0) {
						cigar.append(""+subjectStr.length()+""+deletionOp);
					} else if (queryStr.length()>0) {
						cigar.append(""+queryStr.length()+""+insertionOp);
					}
				}
				nextMatchLength+=kmerLength;
				subjectNext = kmerHit.getStart()+kmerLength;
				queryNext = kmerHit.getQueryIdx()+kmerLength;
			}
			
			//System.out.println("Processed Kmer hit at pos: "+kmerHit.getQueryIdx()+" query next: "+queryNext+" subject next: "+subjectNext);
		}
		if(nextMatchLength>0) {
			cigar.append(""+nextMatchLength+""+matchOp);
			nextMatchLength = 0;
		}
		//int alnFirst = alnStart+1;
		int alnLast = subjectNext;
		//System.out.println("Aligned query. first: "+alnFirst+" last: "+alnLast+" CIGAR: "+cigar+" query next: "+queryNext+" query length: "+query.length());
		if(queryNext<query.length()) {
			//TODO: check if it is worth to align the sequence end
			/*
			if(subjectNext<subject.length()) {
				String queryStr = query.substring(queryNext);
				String subjectStr = subject.substring(subjectNext,Math.min(clusterLast, subject.length()));
				//TODO: Softclip if it starts with indel
				System.out.println("Aligning end of length "+subjectStr.length()+" of subject subsequence with total length: "+subject.length()+" to end with length "+queryStr.length()+" of query with total length: "+query.length());
				String [] alignedFragments = aligner.getAlignment(subjectStr, queryStr);
				String cigarSegment = buildCigar(alignedFragments[0],alignedFragments[1]);
				
				cigar.append(cigarSegment);	
			} else {*/
				//Ignore last bp
			int remainder = query.length()-queryNext;
			cigar.append(""+remainder+""+softClipOp);
			//}
		}
		ReadAlignment finalAlignment = new ReadAlignment(subjectName, alnStart+1, alnLast, query.length(), 0);
		finalAlignment.setReadCharacters(query);
		finalAlignment.setQualityScores(RawRead.generateFixedQSString('5', query.length()));
		finalAlignment.setCigarString(cigar.toString());
		return finalAlignment;
	}

	private String buildCigar(String subjectAln, String queryAln) {
		StringBuilder cigar = new StringBuilder();
		char nextOperator = 0;
		int nextLength = 0;
		for(int i=0;i<subjectAln.length();i++) {
			char subjectChar = subjectAln.charAt(i);
			char queryChar = queryAln.charAt(i);
			char op = ReadAlignment.ALIGNMENT_CHAR_CODES.charAt(ReadAlignment.ALIGNMENT_MATCH);
			if(subjectChar == AlignmentConstantGap.GAP_CHARACTER) {
				op = ReadAlignment.ALIGNMENT_CHAR_CODES.charAt(ReadAlignment.ALIGNMENT_INSERTION);
			} else if(queryChar == AlignmentConstantGap.GAP_CHARACTER) {
				op = ReadAlignment.ALIGNMENT_CHAR_CODES.charAt(ReadAlignment.ALIGNMENT_DELETION);
			}
			if(op != nextOperator) {
				if(nextLength>0) {
					cigar.append(""+nextLength+""+nextOperator);
				}
				nextOperator = op;
				nextLength = 0;
			}
			nextLength++;
		}
		if (nextLength>0) cigar.append(""+nextLength+""+nextOperator);
		return cigar.toString();
	}
}
