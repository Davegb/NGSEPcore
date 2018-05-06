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

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Stack;

import ngsep.main.CommandsDescriptor;
import ngsep.sequences.DNAMaskedSequence;
import ngsep.sequences.FMIndex;
import ngsep.sequences.KmersCounter;
import ngsep.sequences.RawRead;
import ngsep.sequences.io.FastqFileReader;

/**
 * Program to align reads to a refernece genome
 * @author German Andrade
 * @author Jorge Duitama
 */
public class ReadsAligner {

	static final int SEARCH_KMER_LENGTH = 15;
	static final double MIN_ACCURACY =0.5;

	public static void main(String[] args) throws Exception 
	{
		ReadsAligner instance = new ReadsAligner();
		int i = CommandsDescriptor.getInstance().loadOptions(instance, args);
		String fMIndexFile = args[i++];
		String readsFile = args[i++];
		String outFile = args[i++];
		try (PrintStream out = new PrintStream(outFile)){
			instance.alignReads(fMIndexFile, readsFile, out);
		}
	}
	/**
	 * Aligns readsFile with the fMIndexFile
	 * @param fMIndexFile Binary file with the serialization of an FMIndex
	 * @param readsFile Fastq file with the reads to align
	 * @param out
	 * @throws IOException
	 */
	public void alignReads( String fMIndexFile, String readsFile, PrintStream out) throws IOException {
		FMIndex fMIndex = FMIndex.loadFromBinaries(fMIndexFile);
		int totalReads = 0;
		int readsAligned = 0;
		int uniqueAlignments=0;
		long time = System.currentTimeMillis();
		try (FastqFileReader reader = new FastqFileReader(readsFile)) {
			Iterator<RawRead> it = reader.iterator();
			while(it.hasNext()) {
				RawRead read = it.next();
				int numAlns = alignRead(fMIndex, read, out);
				totalReads++;
				if(numAlns>0) readsAligned++;
				if(numAlns==1) uniqueAlignments++;
			}
		}
		System.out.println("Total reads: "+totalReads);
		System.out.println("Reads aligned: "+readsAligned);
		System.out.println("Unique alignments: "+uniqueAlignments);
		System.out.println("Overall alignment rate: "+(100.0*readsAligned/(double)totalReads)+"%");
		double seconds = (System.currentTimeMillis()-time);
		seconds /=1000;
		System.out.println("Time: "+seconds+" seconds");

	}



	private int alignRead(FMIndex fMIndex, RawRead read, PrintStream out) {
		List<ReadAlignment> alignments = search(fMIndex, read);
		int i=0;
		for (ReadAlignment aln: alignments) {
			String readSeq = read.getSequenceString();
			String qual = read.getQualityScores();
			if(aln.isNegativeStrand()) {
				readSeq = DNAMaskedSequence.getReverseComplement(readSeq).toString();
				qual = new StringBuilder(qual).reverse().toString();
			}
			if(i>0) aln.setFlags(aln.getFlags()+ReadAlignment.FLAG_SECONDARY);
			out.println(
					//1.query name
					read.getName()+"\t"+

					//2.Flag
					aln.getFlags()+"\t"+

					//3.reference sequence name
					aln.getSequenceName()+"\t"+

					//4.POS
					aln.getFirst()+"\t"+

					//5.MAPQ
					"255\t"+

					//6.CIGAR
					read.getLength()+"M\t"+

					//7. RNEXT
					"*\t"+

					//8. PNEXT
					"0\t"+

					//9. TLEN
					"0\t"+

					//10. SEQ
					readSeq+"\t"+

					//11. QUAL
					qual

					);
			i++;
		}
		return alignments.size();
	}

	public List<ReadAlignment> search (FMIndex fMIndex, RawRead read) {
		return kmerBasedInexactSearchAlgorithm(fMIndex, read);
	}

	public List<ReadAlignment> exactSearch (FMIndex fMIndex, RawRead read) {
		return fMIndex.search(read.getSequenceString());
	}

	/**
	 * First approach to allow inexact search
	 * It iterates the Sequences of the genome and if there is at least MIN_ACCURACY percentage of the kmers
	 * it allow the alignment with the first an the last position of the kmers ocurrence
	 * @return 
	 */
	private List<ReadAlignment> kmerBasedInexactSearchAlgorithm (FMIndex fMIndex, RawRead read) 
	{
		//System.out.println("KmersCounter.extractKmers: "+read.getCharacters().toString().length());
		CharSequence[] kmers = KmersCounter.extractKmers(read.getCharacters().toString(), SEARCH_KMER_LENGTH, true);

		List<ReadAlignment> finalAlignments =  new ArrayList<>();
		if(kmers!=null)
		{
			int kmersCount=((kmers.length/SEARCH_KMER_LENGTH)+1);

			HashMap<String, List<KmerAlignment>> seqHits = getSequenceHits(fMIndex, read,kmers);

			//Processing part

			KmerAlignmentComparator cmp = KmerAlignmentComparator.getInstance();
			Set<String> keys= seqHits.keySet();
			Iterator <String> iterator =keys.iterator();
			List<KmerAlignment> alns;

			while (iterator.hasNext())
			{
//				System.out.println("---------------"+kmersCount+"---------------------");
				String sequenceName=iterator.next();

				alns = seqHits.get(sequenceName);
				Collections.sort(alns,cmp);

				Stack<KmerAlignment> stack = new Stack<KmerAlignment>();
				KmerAlignment actual;
				double percent;
				for (int i = 0; i < alns.size(); i++) 
				{
					actual=alns.get(i);
					if(stack.isEmpty()||actual.getKmerNumber()>stack.peek().getKmerNumber())
					{
						stack.push(actual);
					}
					else 
					{
						percent = (double) stack.size()/kmersCount;
						if(percent>=MIN_ACCURACY)
						{
							StringBuilder stringBuilder = new StringBuilder();
							
							KmerAlignment[] arr=new KmerAlignment[stack.size()];
							stack.toArray(arr);
							for (int j = arr.length-1; j >=0; j--) 
							{
								stringBuilder.append(arr[j].getReadAlignment().getReadCharacters());
							}
							int first = arr[arr.length-1].getReadAlignment().getFirst();
							int last = arr[0].getReadAlignment().getLast();
							String sequenceChunk = fMIndex.getSequenceSubString(sequenceName,first,last);
							System.console().readLine();
//							String ref; //= fMIndex.get
							String seq = stringBuilder.toString();
//							String aln = smithWatermanLocalAlingMent(ref, seq);
							finalAlignments.add(new ReadAlignment(sequenceName, first, last, last-first, 0));
						}
					}
				}
			}
		}


		return finalAlignments;
	}

	/**
	 * It basically get the no overlapping kmers of  SEARCH_KMER_LENGTH length in the @param read 
	 * and find each alignment of each kmer using the @param fMIndex, saves the alignments in hashmap
	 * @param kmers
	 * @return HashMap with key SequenceName and value a List of alignments that has the kmer value.
	 */
	private HashMap<String, List<KmerAlignment>> getSequenceHits(FMIndex fMIndex, RawRead read,CharSequence[] kmers) {

		HashMap<String,List<KmerAlignment>> seqHits =  new HashMap<String,List<KmerAlignment>>();

		//Avoid overlaps
		for (int i = 0; i < kmers.length; i+=SEARCH_KMER_LENGTH) 
		{
			//Exit loop if kmers[i] is null
			if(kmers[i]==null)
				continue;

			String kmer =kmers[i].toString();


			//Where is located the kmer in exact way
			List<ReadAlignment> regions=fMIndex.search(kmer);

			for(ReadAlignment aln:regions)
			{

				KmerAlignment kmerAlignment = new KmerAlignment(i,aln);
				List<KmerAlignment> seqAlns = seqHits.get(aln.getSequenceName());

				if(seqAlns==null) {
					seqAlns = new ArrayList<>();
					seqHits.put(aln.getSequenceName(), seqAlns);
				}
				seqAlns.add(kmerAlignment);
			}
		}
		return seqHits;
	}
	
	
	
	
	private String smithWatermanLocalAlingMent(String reference, String secuence) 
	{
		//Pila que guarda las letras de la palabra1
		Stack<String> pila1A = new Stack<>();

		//Pila que guarda las letras de la palabra2
		Stack<String> pila2A = new Stack<>();

		//Matriz que tiene 0 si palabra1.charAt(i)==palabra2.charAt(j) y uno de lo contrario
		int[][] diagonales = new int[reference.length()][secuence.length()];


		for (int i = 0; i < diagonales.length; i++) 
		{
			char actualPalabra1=reference.charAt(i);
			pila1A.push(actualPalabra1+"");

			for (int j = 0; j < diagonales[i].length; j++) 
			{
				char actualPalabra2=secuence.charAt(j);
				if(i==0)
					pila2A.push(actualPalabra2+"");

				diagonales[i][j]= actualPalabra1==actualPalabra2 ? 0:1;
			}
		}

		//Matriz para programación dinámica guarda el menor peso para ir de 0,0 a i,j
		int[][] A = new int[diagonales.length+1][diagonales[0].length+1]; 

		for (int i = 0; i < A.length; i++) 
		{	
			for (int j = 0; j < A[0].length; j++) 
			{
				//Caso base, el costo para llegar a 0,0 es 0
				if(i==0 && j==0)
				{
					A[i][j]=0;
				}
				//Semánticamente es borrar una letra de la primera palabra
				//Caso base, el costo para llegar a 0,j es 1+ costo(0,j-1)
				//Esto es moverse en horizontal es decir por las columnas -->
				else if(i==0)
				{
					A[i][j]= 1 + A[i][j-1];
				}
				//Semánticamente es insertar una letra en la segunda palabra
				//Caso base, el costo para llegar a i,0 es 1 + costo(i-1,0)
				//Esto es moverse en verical es decir por las filas |
				//												    v	
				else if(j==0)
				{
					A[i][j]= 1 + A[i-1][j];
				}
				//A este caso se puede llegar desde arriba (i-1,j)
				//Desde la izquierda (i,j-1)
				//O desde la diagonal superior izquierda (i-1,j-1)
				else
				{
					int[] posibilidades= {
							1 						+ A[i-1][j], 	// llegar desde arriba cuesta 1 + lo que cuesta llegar a (i-1,j)
							1 						+ A[i][j-1], 	// llegar desde la izquierda cuesta 1 + lo que cuesta llegar a (i-1,j)
							diagonales[i-1][j-1] 	+ A[i-1][j-1]	// llegar desde la diagonal superior cuesta 1 si las letras son diferentes
									// y 0 si son iguales, + o que cuesta llegar a (i-1,j-1)
					};
					int min = Integer.MAX_VALUE;
					for (int k = 0; k < posibilidades.length; k++) 
					{
						if(posibilidades[k]<min)
						{
							min = posibilidades[k];
						}
					}

					A[i][j]=min;
				}
			}
		}

		//En este punto ya se tiene el costo mínimo para llegar a (A.length-1,A[0].length-1)
		//Ahora hay que devolverse y recordar las desiciones

		//pila que guarda el alineamiento de la palabra1
		Stack<String> pila1 = new Stack<>();

		//pila que guarda el alineamiento de la palabra2
		Stack<String> pila2 = new Stack<>();

		//Guarda la posicion actual en la que va el algoritmo que se devuelte
		//inicialmente está en la esquina inferior derecha, donde está el costo mínimo para llegar a (A.length-1,A[0].length-1)
		int[] r={A.length-1,A[0].length-1};

		//Mientras no lleguemos al inicio siga devolviendose
		while(!(r[0]==0 && r[1]==0))
		{
			//Se desea hallar cual camino tiene menor costo

			try
			{

				//Se revisa si es desde arriba
				if( A [r[0]-1] [r[1]] < A [r[0]][r[1]-1] && A [r[0]-1] [r[1]] < A [r[0]-1] [r[1]-1])
				{
					int[] a = { r[0]-1,r[1]};

					//Se agrega un guion en la palabra 2
					pila2.push("-");

					//Se mete la siguiente letra en la palabra 1
					pila1.push(pila1A.pop());

					// se actualiza r que es la posición actual
					r=a;
				}
				//Se revisa si es desde la izquierda
				else if( A [r[0]] [r[1]-1] < A [r[0]-1][r[1]] && A [r[0]] [r[1]-1] < A [r[0]-1][r[1]-1])
				{
					int[] a = { r[0],r[1]-1};

					//Se agrega un guion en la palabra 1
					pila1.push("-");

					//Se mete la siguiente letra en la palabra 2
					pila2.push(pila2A.pop());

					// se actualiza r que es la posición actual
					r=a;
				}
				//Se revisa la diagonal superior izquierda
				else
				{
					int[] a = { r[0]-1,r[1]-1};

					//Se mete la siguiente letra en la palabra 1
					pila1.push(pila1A.pop());

					//Se mete la siguiente letra en la palabra 2
					pila2.push(pila2A.pop());

					// se actualiza r que es la posición actual
					r=a;
				}
			}
			catch (Exception e) 
			{
//				e.printStackTrace();
				// se trató de llegar a posición negativa

				//si hay camino desde arriba
				if(r[0]-1>=0)
				{
					int[] a = { r[0]-1,r[1]};

					//Se agrega un guion en la palabra 2
					pila2.push("-");

					//Se mete la siguiente letra en la palabra 1
					pila1.push(pila1A.pop());

					// se actualiza r que es la posición actual
					r=a;
				}
				//si no debe haber camino por la izquierda
				else
				{
					int[] a = { r[0],r[1]-1};

					//Se agrega un guion en la palabra 1
					pila1.push("-");

					//Se mete la siguiente letra en la palabra 2
					pila2.push(pila2A.pop());

					// se actualiza r que es la posición actual
					r=a;
				}
			}

		}
		//Ya se tienen las palabras en las pilas, ahora se voltean y se imprimen

		String p1="";
		String p2="";
		while(!pila1.isEmpty() && !pila2.isEmpty())
		{
			p1+=pila1.pop();
			p2+=pila2.pop();
		}

//		System.out.println(p1);
//		System.out.println(p2);
		return p2;
	}
}
