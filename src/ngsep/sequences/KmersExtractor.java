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
package ngsep.sequences;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import ngsep.main.CommandsDescriptor;
import ngsep.main.OptionValuesDecoder;
import ngsep.main.ProgressNotifier;
import ngsep.math.Distribution;
import ngsep.sequences.io.FastaFileReader;
import ngsep.sequences.io.FastqFileReader;

/**
 * 
 * @author Jorge Duitama
 *
 */
public class KmersExtractor {
	
	// Constants for default values
	public static final byte DEF_KMER_LENGTH = 15;
	public static final int DEF_MIN_KMER_COUNT = 5;
	public static final byte INPUT_FORMAT_FASTQ=0;
	public static final byte INPUT_FORMAT_FASTA=1;
	
	// Logging and progress
	private Logger log = Logger.getLogger(KmersExtractor.class.getName());
	private ProgressNotifier progressNotifier=null;
	
	// Parameters
	private String outputPrefix = null;
	private int kmerLength = DEF_KMER_LENGTH;
	private int minKmerCount = DEF_MIN_KMER_COUNT;
	private boolean onlyForwardStrand=false;
	private byte inputFormat = INPUT_FORMAT_FASTQ;
	private boolean onlyDNA = false;
	private boolean ignoreLowComplexity = false;
	
	// Model attributes
	private KmersMap kmersMap = null;
	
	private static final DNASequence EMPTYDNASEQ = new DNASequence();
	
	
	// Get and set methods
	public Logger getLog() {
		return log;
	}
	public void setLog(Logger log) {
		this.log = log;
	}
	
	public ProgressNotifier getProgressNotifier() {
		return progressNotifier;
	}
	public void setProgressNotifier(ProgressNotifier progressNotifier) {
		this.progressNotifier = progressNotifier;
	}
	
	public String getOutputPrefix() {
		return outputPrefix;
	}
	public void setOutputPrefix(String outputPrefix) {
		this.outputPrefix = outputPrefix;
	}
	public int getKmerLength() {
		return kmerLength;
	}
	public void setKmerLength(int kmerLength) {
		this.kmerLength = kmerLength;
		kmersMap=null;
	}
	public void setKmerLength(String value) {
		setKmerLength((int)OptionValuesDecoder.decode(value, Integer.class));
	}
	
	public int getMinKmerCount() {
		return minKmerCount;
	}
	public void setMinKmerCount(int minKmerCount) {
		this.minKmerCount = minKmerCount;
	}
	public void setMinKmerCount(String value) {
		setMinKmerCount((int)OptionValuesDecoder.decode(value, Integer.class));
	}
	public boolean isOnlyForwardStrand() {
		return onlyForwardStrand;
	}
	public void setOnlyForwardStrand(boolean onlyForwardStrand) {
		this.onlyForwardStrand = onlyForwardStrand;
	}
	public void setOnlyForwardStrand(Boolean onlyForwardStrand) {
		this.setOnlyForwardStrand(onlyForwardStrand.booleanValue());
	}
	public byte getInputFormat() {
		return inputFormat;
	}
	public void setInputFormat(byte inputFormat) {
		if(inputFormat!=INPUT_FORMAT_FASTA && inputFormat!=INPUT_FORMAT_FASTQ) throw new IllegalArgumentException("Invalid input format "+inputFormat);
		this.inputFormat = inputFormat;
	}
	public void setInputFormat(String value) {
		this.setInputFormat((byte) OptionValuesDecoder.decode(value, Byte.class));
	}
	
	public boolean isOnlyDNA() {
		return onlyDNA;
	}
	public void setOnlyDNA(boolean onlyDNA) {
		this.onlyDNA = onlyDNA;
		kmersMap=null;
	}
	public void setOnlyDNA(Boolean onlyDNA) {
		this.setOnlyDNA(onlyDNA.booleanValue());
	}
	public boolean isIgnoreLowComplexity() {
		return ignoreLowComplexity;
	}
	public void setIgnoreLowComplexity(boolean ignoreLowComplexity) {
		this.ignoreLowComplexity = ignoreLowComplexity;
	}
	public void setIgnoreLowComplexity(Boolean ignoreLowComplexity) {
		this.setIgnoreLowComplexity(ignoreLowComplexity.booleanValue());
	}
	/**
	 * @return the hashKmers
	 */
	public KmersMap getKmersMap() {
		return kmersMap;
	}
	/**
	 * Receives the parameters from the command line interface and distributes the duties
	 * @param args
	 * @throws Exception 
	 */
	public static void main (String [ ] args) throws Exception {

		KmersExtractor instance = new KmersExtractor();
		//Parameters
		int k=CommandsDescriptor.getInstance().loadOptions(instance, args);
		List<String> files = new ArrayList<>();
		for(;k<args.length;k++) {
			files.add(args[k]);
		} 
		instance.processFiles(files);
		instance.saveResults();
	}
	
	/**
	 * Processes a list of input files as fasta or fastq and updates the kmers table
	 * @param files List of names of the files to process.
	 * @throws IOException If a file can not be read
	 */
	public void processFiles(List<String> files) throws IOException {
		logParameters ();
		if(files.size()==1 && "-".equals(files.get(0))) processFastqFile(System.in);
		for(String filename:files) processFile(filename);
		
	}
	
	private void logParameters() {
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		PrintStream out = new PrintStream(os);
		out.println("Prefix for output files:"+ outputPrefix);
		out.println("K-mer length: "+ kmerLength);
		out.println("Minimum count to save k-mer: "+ minKmerCount);
		if (onlyForwardStrand) out.println("Extract k-mers only from the forward strand");
		if (inputFormat == INPUT_FORMAT_FASTQ)  out.println("Fastq format");
		if (inputFormat == INPUT_FORMAT_FASTA)  out.println("Fasta format");
		if (ignoreLowComplexity) out.println("Ignore low complexity k-mers");
		log.info(os.toString());
		
	}
	/**
	 * Processes the file with the given name as fasta or fastq and updates the kmers table
	 * @param sequenceFileName Name of the file with the sequences to process.
	 * @throws IOException If the file can not be read
	 */
	public void processFile(String filename) throws IOException {
		initializeMap();
		//Is fasta or fastq? and read it
		if(inputFormat==INPUT_FORMAT_FASTA){
			processFastaFile(filename);
		} else {
			processFastqFile(filename);	
		}
	}
	
	public void initializeMap() {
		if(kmersMap==null) {
			if(isOnlyDNA() && kmerLength<=15) kmersMap = new ShortArrayDNAKmersMapImpl((byte)kmerLength);
			else kmersMap = new DefaultKmersMapImpl();
		}
	}
	/**
	 * Processes the file with the given name as fastq and updates the kmers table
	 * @param filename Name of the file with the sequences to process.
	 * @throws IOException If the file can not be read
	 */
    public void processFastqFile(String filename) throws IOException {
    	initializeMap();
		try (FastqFileReader reader = new FastqFileReader(filename)) {
			Iterator<RawRead> it = reader.iterator();
			for (int i=0;it.hasNext();i++) {
				RawRead read = it.next();
				countSequenceKmers (read);
				if((i+1)%100==0) log.info("Processed "+(i+1)+" sequences");
			}
		}
	 }
    
	/**
     * Process the given input stream to get kmers count sequence by sequence
     * @param fis Input stream in fastq format
     * @throws IOException if there is an error reading the stream
     */
	public void processFastqFile(InputStream fis) throws IOException {
		initializeMap();
		try (FastqFileReader reader = new FastqFileReader(fis)) {
			Iterator<RawRead> it = reader.iterator();
			for (int i=0;it.hasNext();i++) {
				RawRead read = it.next();
				countSequenceKmers (read);
				if((i+1)%100==0) log.info("Processed "+(i+1)+" sequences");
			}
		}
	}
	
	/**
	 * Processes the file with the given name as fasta and updates the kmers table
	 * @param filename Name of the file with the sequences to process.
	 * @throws IOException If the file can not be read
	 */
    private void processFastaFile(String filename) throws IOException {
    	initializeMap();
    	try (FastaFileReader reader = new FastaFileReader(filename)) {
			Iterator<QualifiedSequence> it = reader.iterator();
			while(it.hasNext()) {
				QualifiedSequence seq = it.next();
				log.info("Processing sequence "+seq.getName());
				countSequenceKmers(seq);
				log.info("Processed sequence "+seq.getName()+" total k-mers: "+kmersMap.size());
			}
    	}
	}
    public void processQualifiedSequences(List<QualifiedSequence> sequences) {
    	initializeMap();
    	int i = 0;
    	for(QualifiedSequence qseq:sequences) {
    		countSequenceKmers(qseq);
    		i++;
    		if(i%100==0) log.info("Processed "+i+" sequences");
    	}
    }
	public void countSequenceKmers(QualifiedSequence qseq) {
		//TODO: Process in chuncks if too big
		//Forward		
		CharSequence sequence = qseq.getCharacters();
		countSequenceKmers(sequence.toString());
		//Reverse complement
		if(!onlyForwardStrand){
			CharSequence reverseSequence = DNAMaskedSequence.getReverseComplement(sequence);
			countSequenceKmers(reverseSequence.toString());
		}
	}
	/**
	 * Updates the k-mers table using the information of the given sequence
	 * @param seq CharSequence object to extract the k-mers
	 */
	public void countSequenceKmers(String seq)
	{
		if(kmersMap==null) initializeMap();
		int seqLength = seq.length();
		
		if(seqLength < kmerLength) {
			log.warning("Sequence "+seq+" smaller than k-mer length");
			return;
		}
		if(onlyDNA && !ignoreLowComplexity && kmerLength<=15) {
			//Faster alternative
			Map<Integer,Long> codes = extractDNAKmerCodes(seq, kmerLength, 0, seq.length());
			for(long code:codes.values()) {
				ShortArrayDNAKmersMapImpl skmersMap = (ShortArrayDNAKmersMapImpl) kmersMap;
				skmersMap.addCodeOccurance(code);
			}
			return;
		}
		String [] kmers = extractKmers(seq, kmerLength, 1, 0, seq.length(), false, onlyDNA, ignoreLowComplexity);
		synchronized (kmersMap) {
			for(String kmer:kmers) {
				if(kmer==null) continue;
				if(kmer.length()<=15) kmersMap.addOcurrance(kmer);
				else kmersMap.addOcurrance(pack(kmer));
			}
		}
	}
	
	/**
	 * Extracts the k-mers present in the given sequence
	 * @param source Sequence to process. The sequence is processed as is (no uppercase or other transformation).
	 * @param kmerLength Length of the output sequences. It must be less or equal than the length of the sequence
	 * @param offset Distance between kmers
	 * @param forceLast Includes last k-mer even if it does not meet the offset requirement
	 * @param onlyDNA Tells if only k-mers within the DNA alphabet should be considered
	 * @param ignoreLowComplexity If true, ignores kmers having low complexity sequences 
	 * @return Map<Integer,String> Map of kmers indexed and sorted by (zero based) start position in the source sequence
	 */
	public static Map<Integer,String> extractKmersAsMap (String source, int kmerLength, int offset, boolean forceLast, boolean onlyDNA, boolean ignoreLowComplexity) {
		return extractKmersAsMap(source, kmerLength, offset, 0, source.length(), forceLast, onlyDNA, ignoreLowComplexity);
				
	}
	/**
	 * Extracts the k-mers present in the given sequence
	 * @param source Sequence to process. The sequence is processed as is (no uppercase or other transformation).
	 * @param kmerLength Length of the output sequences. It must be less or equal than the length of the sequence
	 * @param offset Distance between kmers
	 * @param start index to extract kmers
	 * @param end index to extract kmers
	 * @param forceLast Includes last k-mer even if it does not meet the offset requirement
	 * @param onlyDNA Tells if only k-mers within the DNA alphabet should be considered
	 * @param ignoreLowComplexity If true, ignores kmers having low complexity sequences 
	 * @return Map<Integer,String> Map of kmers indexed and sorted by (zero based) start position in the source sequence
	 */
	public static Map<Integer,String> extractKmersAsMap (String source, int kmerLength, int offset, int start, int end, boolean forceLast, boolean onlyDNA, boolean ignoreLowComplexity) {
		validateLimits(source, start, end);
		int n = source.length();
		Map<Integer,String> kmersMap = new LinkedHashMap<Integer, String>();
		if (n < kmerLength) return kmersMap;
		int lastKmerStart = end - kmerLength;
		int lastPos = 0;
		for(int i = start; i <=lastKmerStart; i+=offset) {
			String kmer = source.substring(i, i+kmerLength);
			if(passFilters(kmer, onlyDNA, ignoreLowComplexity)) kmersMap.put(i, kmer);
			lastPos = i;
		}
		if(forceLast && lastPos < lastKmerStart) {
			String kmer = source.substring(lastKmerStart, end );
			if(passFilters(kmer, onlyDNA, ignoreLowComplexity)) kmersMap.put(lastKmerStart, kmer);
		}

		return kmersMap;
	}
	private static int validateLimits(CharSequence source, int start, int end) {
		if(start >=end) throw new IllegalArgumentException("Start index must be smaller than end index. Given start: "+start+" given end: "+end);
		if(start < 0) throw new IllegalArgumentException("Start index must be positive. Given start: "+start);
		int n = source.length();
		if(end > n) throw new IllegalArgumentException("End index must be at most equal to source length. Given end: "+end+" length: "+n);
		return n;
	}
	/**
	 * Extracts the codes representing DNA kmers from the given sequence
	 * @param source Sequence to extract kmers. Usually a String but it works with StringBuilder or other types of sequences
	 * @param kmerLength must be at most 31 to allow unique encoding of DNA kmers
	 * @param start of the source sequence
	 * @param end of the source sequence
	 * @return Map<Integer,Long> with kmer codes indexed by start position
	 */
	public static Map<Integer,Long> extractDNAKmerCodes (CharSequence source, int kmerLength, int start, int end) {
		validateLimits(source, start, end);
		if(kmerLength>31) throw new IllegalArgumentException("This method only works with kmer lengths up to 31");
		int n = source.length();
		Map<Integer,Long> kmerCodesMap = new LinkedHashMap<Integer, Long>();
		if (n < kmerLength) return kmerCodesMap;
		int lastKmerStart = end - kmerLength;
		long lastCode = -1;
		for(int i = start; i <=lastKmerStart; i++) {
			long code;
			if(lastCode==-1) {
				CharSequence kmer = source.subSequence(i, i+kmerLength);
				if (!DNASequence.isDNA(kmer)) continue;
				code = AbstractLimitedSequence.getHash(kmer, 0, kmerLength, EMPTYDNASEQ);
			} else {
				char lastCharNextKmer = source.charAt(i+kmerLength-1);
				if(!DNASequence.isInAlphabeth(lastCharNextKmer)) {
					lastCode = -1;
					//Ignore all kmers spanning the non DNA character
					i+=kmerLength-1;
					continue;
				}
				code = AbstractLimitedSequence.getNextHash(lastCode,kmerLength,lastCharNextKmer,EMPTYDNASEQ);
			}
			kmerCodesMap.put(i, code);
			lastCode = code;
		}
		return kmerCodesMap;
	}
	public static Map<Long, Integer> extractLocallyUniqueKmerCodes(CharSequence sequence, int start, int end) {
		Map<Integer,Long> rawCodes = KmersExtractor.extractDNAKmerCodes(sequence, 15, start, end);
		Map<Long, Integer> answer = new LinkedHashMap<Long, Integer>();
		Map<Long, Integer> reverseMap = new HashMap<Long,Integer>();
		Set<Integer> multiple = new HashSet<>();
		for(int i=start;i<end;i++) {
			Long code = rawCodes.get(i);
			if(code == null) {
				multiple.add(i);
				continue;
			}
			Integer previousStart = reverseMap.get(code);
			if(previousStart!=null) {
				multiple.add(i);
				continue;
			}
			reverseMap.put(code,i);
		}
		for(int i=start;i<end;i++) {
			Long code = rawCodes.get(i);
			if(code!=null && !multiple.contains(i)) {
				answer.put(code,i);
			}
		}
		return answer;
	}
	/**
	 * Extracts the k-mers present in the given sequence
	 * @param source Sequence to process. The sequence is processed as is (no uppercase or other transformation).
	 * @param kmerLength Length of the output sequences. It must be less or equal than the length of the sequence
	 * @param offset Distance between kmers
	 * @param forceLast Includes last k-mer even if it does not meet the offset requirement
	 * @param onlyDNA Tells if only k-mers within the DNA alphabet should be considered
	 * @param ignoreLowComplexity If true, ignores kmers having low complexity sequences
	 * @return CharSequence [] Array of k-mers within the source sequence. The index in the array corresponds
	 * to the index in the sequence of the start of the k-mer
	 */
	public static String [] extractKmers (String source, int kmerLength, int offset, int start, int end, boolean forceLast, boolean onlyDNA, boolean ignoreLowComplexity) {
		validateLimits(source, start, end);
		int n = source.length();
		if(n<kmerLength) return new String[0];
		int lastKmerStart = end - kmerLength; 
		String [] kmers = new String [lastKmerStart+1];
		Arrays.fill(kmers, null);
		for(int i = start; i <=lastKmerStart; i+=offset) {
			String kmer = source.substring(i, i+kmerLength);
			if (passFilters(kmer, onlyDNA, ignoreLowComplexity)) kmers[i]=kmer;
		}
		if(forceLast && kmers[lastKmerStart]==null) {
			String kmer = source.substring(lastKmerStart, end );
			if (passFilters(kmer, onlyDNA, ignoreLowComplexity)) kmers[lastKmerStart]=kmer;
		}
		return kmers;
	}
	
	private static boolean passFilters(String kmer, boolean onlyDNA, boolean ignoreLowComplexity) {
		if(onlyDNA && !DNASequence.isDNA(kmer)) return false;
		if(ignoreLowComplexity && isLowComplexity(kmer)) return false;
		return true;
	}
	
	public static CharSequence pack (String initialKmer) {
		CharSequence kmer = initialKmer;
		try {
			if(initialKmer.length()<=31) {
				kmer = new DNAShortKmer(kmer);
			} else {
				kmer = new DNASequence(kmer);
			}
		} catch (IllegalArgumentException e) {
		}
		return kmer;
	}
	public static final boolean isLowComplexity(String kmerStr) {
		// TODO: Actually calculate complexity
		if(kmerStr.contains("AAAAAAAA")) return true;
		if(kmerStr.contains("TTTTTTTT")) return true;
		if(kmerStr.contains("TATATATA")) return true;
		return false;
	}
	public void saveResults () throws IOException {
		log.info("Calculating distribution of abundances from "+kmersMap.size()+" k-mers");
		Distribution kmerSpectrum = kmersMap.calculateAbundancesDistribution();
		try (PrintStream out=new PrintStream(outputPrefix+"_kmers_distribution.txt")) {
			out.println("Kmer_frequency\tNumber_of_distinct_kmers");
			kmerSpectrum.printDistributionInt(out);
		}
		kmersMap.filterKmers(minKmerCount);
		log.info("Saving "+kmersMap.size()+" filtered k-mers with minimum count "+minKmerCount);
		try (OutputStream os = new GZIPOutputStream(new FileOutputStream(outputPrefix+"_kmers.txt.gz"));
			 PrintStream out = new PrintStream(os)) {
			kmersMap.save(out);
		}
		
	}	
}
