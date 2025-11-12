
/*
	LZSS compression
	Copyright 2003 Zip-Technology.com

	@Author Ronan Merien <ronan.merien@zip-technology.com>
	@Version 1.2, 2003/10/15
*/

import java.io.*;
import java.util.*;
import binary.*;
import java.util.zip.*;
import zpp.*;

/**
	<p>The compression used a combination of the LZSS algorithm, Huffman coding and word references from a static dictionnary.</p>
	<p>The LZSS algorithm may use a reference to a duplicated string (256 bytes max) occurring in a previous block, up to 64K input bytes before.</p>

	<p>The compressed data consists of a series of elements of two types: literal bytes (of strings that have not been detected
	as duplicated within the previous input bytes), and pointers to duplicated strings, where a pointer is represented as a pair <length, backward distance>.</p>

	<p>Each type of value (literals, distances, and lengths) in the compressed data is represented
	using a Huffman code, using one code tree for literals and lengths and a separate code tree for distances.</p>
	<p>The code trees appear in a compact form just before the compressed data.</p>
*/
public class LZCompress {

	String logFile = "log.txt";

	byte old_code_ascii;
	byte code_ascii;
/*
	byte[] tampon;
	int out = 0;
	int in = 0;

	int[] tampon_dico;
	int out_dico = 0;
	int in_dico = 0;
*/
	static int INDEX_BIT_COUNT = 16;
	static int LENGTH_BIT_COUNT = 8;

	static int WINDOW_SIZE = ( 1 << INDEX_BIT_COUNT); // Window (saved as a tree representation) of 65536 bytes
	static int LOOK_AHEAD_SIZE = ( 1 << LENGTH_BIT_COUNT); // Input buffer length of 256 bytes

	static int BREAK_EVEN = 3;
	static int TREE_ROOT = WINDOW_SIZE;
	static int UNUSED = 0;

	static int maxBits = 20;
	int[] count = new int[maxBits];
	int[] next_code = new int[maxBits];

	BinaryTree[] tabStatistics_len;
	BinaryTree[] tabStatistics_dis;

	static int len_max = 281;
	static int dis_max = 32;

	BinaryInputStream bis;
	BinaryOutputStream bos;

	// this vector will store the compress code generated during the pass
	Vector gen;

	int[] window;

	// the back window is saved as a tree representation.
	int[] tree_parent;
	int[] tree_smaller_child;
	int[] tree_larger_child;

	int look_ahead_bytes = 0;
	int current_distance = 1;
	int replace_count = 0;
	int match_length = 0;
	int match_distance = 0;
	// int match_node = 0;

	public LZCompress (String inFile, OutputStream os)
	{
		try {
			if (logFile != null) redirectOutput();

			// initialisation of the string trees
			window = new int[WINDOW_SIZE];
			//tampon = new byte[4];
			//tampon_dico = new int[25];

			tree_parent = new int[WINDOW_SIZE+1];
			tree_smaller_child= new int[WINDOW_SIZE+1];
			tree_larger_child = new int[WINDOW_SIZE+1];

			for (int i=0; i < WINDOW_SIZE+1; i++) {
				tree_parent[i] = 0;
				tree_smaller_child[i] = 0;
				tree_larger_child[i] = 0;
			}

			tree_larger_child[TREE_ROOT] = 1;
			tree_parent[1] = TREE_ROOT;
			tree_larger_child[1] = UNUSED;
			tree_smaller_child[1] = UNUSED;

			// first pass : get statistics from input stream
			tabStatistics_len = new BinaryTree[len_max];
			for (int i=0; i<len_max; i++) {
				tabStatistics_len[i] = new BinaryTree(i,0);
			}

			tabStatistics_dis = new BinaryTree[dis_max];
			for (int i=0; i<dis_max; i++) {
				tabStatistics_dis[i] = new BinaryTree(i,0);
			}

			for (int i=0; i < maxBits; i++) {
				count[i] = 0;
				next_code[i] = 0;
			}


			// ----------------------------------------------------------------------------------------------------------

			FileInputStream fis = new FileInputStream(inFile);
			BufferedInputStream bufis = new BufferedInputStream(fis);
			bis = new BinaryInputStream(bufis);

			gen = new Vector(fis.available(), 100000);

			try {
				// compress until the end of the input file
				while (true) {
					// filling the buffer
					while (look_ahead_bytes < LOOK_AHEAD_SIZE) {
						window[current_distance + look_ahead_bytes] = readNext();
						look_ahead_bytes ++;
					}

					int tmp_match_length;
					int tmp_match_distance;
					// int tmp_match_node;

					while (look_ahead_bytes > 0) {

						// lazy matching

						tmp_match_length = match_length;
						tmp_match_distance = match_distance;
						// tmp_match_node = match_node;

						searchString(mod_window(current_distance + 1));

						if (tmp_match_length >= match_length) {
							// if (tmp_match_node >= match_node)
							// match_node = tmp_match_node;
							match_length = tmp_match_length;
							match_distance = tmp_match_distance;

							find_match();
						}
						else {
							match_length = 0;

							find_match();

							deleteString(mod_window(current_distance + LOOK_AHEAD_SIZE));

							window[mod_window(current_distance + LOOK_AHEAD_SIZE)] = readNext();

							current_distance = mod_window(current_distance + 1);
							addString(current_distance);

							find_match();
						}

						while (replace_count > 0) {
							deleteString(mod_window(current_distance + LOOK_AHEAD_SIZE));

							window[mod_window(current_distance + LOOK_AHEAD_SIZE)] = readNext();

							current_distance = mod_window(current_distance + 1);

							addString(current_distance);

							replace_count --;
						} // End While

					} // End While
				} // End While
			}
			catch (EOFException e) {
				System.out.println(e);
				System.out.println("-------------------------------------------------------------------------");

				// working on the remaining buffer
				while (look_ahead_bytes > 0) {

					while (replace_count > 0) {
						deleteString(mod_window(current_distance + LOOK_AHEAD_SIZE));
						look_ahead_bytes --;
						current_distance = mod_window(current_distance + 1);
						addString(current_distance);
						replace_count --;
					}

					if (look_ahead_bytes > 0) {
						find_match();
					}
				}
			}

			tabStatistics_len[256].frequence ++;

			bis.close();


			System.out.println("-----------------------------------------------");
			for (int i=0; i<len_max; i++) {
				System.out.println(Binary.toHexaString((byte) i)
					+ " -> "
					+ tabStatistics_len[i].frequence);
			}

			System.out.println("-----------------------------------------------");
			for (int i=0; i<dis_max; i++) {
				System.out.println(Binary.toHexaString((byte) i)
					+ " -> "
					+ tabStatistics_dis[i].frequence);
			}


			// build the vector
			Vector list = new Vector();
			for (int i=0; i<len_max; i++) {
				if  (tabStatistics_len[i].frequence > 0) {
					list.addElement(tabStatistics_len[i]);
				}
			}
			sort(list,0,list.size()-1);

			Vector list_ref = new Vector();
			for (int i=0; i<dis_max; i++) {
				if  (tabStatistics_dis[i].frequence > 0) {
					list_ref.addElement(tabStatistics_dis[i]);
				}
			}
			sort(list_ref,0,list_ref.size()-1);

			System.out.println("-----------------------------------------------");

			// build the huffman tree

			for (int i=0; i < maxBits; i++) {
				count[i] = 0;
				next_code[i] = 0;
			}

			int size = list.size();

			BinaryTree node_0 = null;
			BinaryTree node_1 = null;
			BinaryTree tree = null;

			for (int k=0; k<size-1; k++) {

				node_0  = (BinaryTree) list.elementAt(0);
				node_1  = (BinaryTree) list.elementAt(1);

				int sum = node_0.frequence + node_1.frequence;

				tree = new BinaryTree(-1, sum, node_0, node_1);
				// tree.toString();

				list.removeElementAt(0);
				list.removeElementAt(0);

				list.insertElementAt(tree, 0);
				sort(list,0,list.size()-1);
			}

			convert_tree_to_code(tree,0);

			// System.out.println("-----------------------------------------------");

			int compressCode = 0;
			count[0] = 0;
			for (int bits = 1; bits < maxBits; bits++) {
				compressCode = (compressCode + count[bits-1]) << 1;
				next_code[bits] = compressCode;
			}

			BinaryTree node = null;
			for (int i=0; i<len_max; i++) {
				node = tabStatistics_len[i];

				if (node != null) {
					int len = node.nbBits;
					if (len != 0) {
						node.compressCode = reverse(next_code[len],len);
						next_code[len]++;
					}
				}
			}

			print(tabStatistics_len);

			// System.out.println("-----------------------------------------------");

			for (int i=0; i < maxBits; i++) {
				count[i] = 0;
				next_code[i] = 0;
			}

			node_0 = null;
			node_1 = null;
			tree 	= null;

			size = list_ref.size();

			for (int k=0; k<size-1; k++) {

				node_0  = (BinaryTree) list_ref.elementAt(0);
				node_1  = (BinaryTree) list_ref.elementAt(1);

				int sum = node_0.frequence + node_1.frequence;

				tree 	= new BinaryTree(-1, sum, node_0, node_1);

				list_ref.removeElementAt(0);
				list_ref.removeElementAt(0);

				list_ref.insertElementAt(tree, 0);
				sort(list_ref,0,list_ref.size()-1);
			}

			if (tree != null) convert_tree_to_code(tree,0);

			compressCode = 0;
			count[0] = 0;
			for (int bits = 1; bits < maxBits; bits++) {
				compressCode = (compressCode + count[bits-1]) << 1;
				next_code[bits] = compressCode;
			}

			for (int i=0; i<dis_max; i++) {
				node = tabStatistics_dis[i];

				if (node != null) {
					int len = node.nbBits;
					if (len != 0) {
						node.compressCode = reverse(next_code[len],len);
						next_code[len]++;
					}
				}
			}

			print(tabStatistics_dis);

			// System.out.println("-----------------------------------------------");

			bos = new BinaryOutputStream(new BufferedOutputStream(os));

			boolean mode_rle = false;
			int compteur_rle = 0;
			// System.out.println("generating huffman codes ...");

			// next, write huffman statistics for length into the output file
			for (int i=0; i<len_max; i++) {
				if (tabStatistics_len[i].frequence>0) {
					if (mode_rle) {
						bos.writeBit(0x00,4);
						bos.writeBit(compteur_rle-1,8); // Pb if more than 256 null codes (?)
						// System.out.println(0 + "," + compteur_rle);
						mode_rle = false;
						compteur_rle = 0;
					}

					BinaryTree element = tabStatistics_len[i];
					if (element.nbBits < 15) {
						bos.writeBit(element.nbBits,4);
					}
					else if ((element.nbBits >= 15) && (element.nbBits < 18)) {
						bos.writeBit(15, 4);
						bos.writeBit(element.nbBits-15,2); // mode (0-> 15, 1-> 16, 2-> 17, 3-> 18+ bits)
					}
					else if (element.nbBits < maxBits) {
						bos.writeBit(15, 4);
						bos.writeBit(3, 2);
						bos.writeBit(element.nbBits-18,2); // mode (0-> 18, 1-> 19 bits)
					}
					// System.out.println(element.nbBits);
				}
				else {
					mode_rle = true;
					compteur_rle ++;
				}
			}

			if (mode_rle) {
				bos.writeBit(0x00,4);
				bos.writeBit(compteur_rle-1,8);
				// System.out.println(0 + "," + compteur_rle);
				mode_rle = false;
				compteur_rle = 0;
			}

			// System.out.println("----------------------------");

			// next, write huffman statistics for distance into the output file
			for (int i=0; i<dis_max; i++) {
				if (tabStatistics_dis[i].frequence > 0) {
					if (mode_rle) {
						bos.writeBit(0x00,4);
						bos.writeBit(compteur_rle-1,5);
						// System.out.println(0 + "," + compteur_rle);
						mode_rle = false;
						compteur_rle = 0;
					}

					BinaryTree element = tabStatistics_dis[i];
					bos.writeBit(element.nbBits, 4);
					// System.out.println(element.nbBits);
				}
				else {
					mode_rle = true;
					compteur_rle ++;
				}
			}

			if (mode_rle) {
				bos.writeBit(0x00,4);
				bos.writeBit(compteur_rle-1,5);
				// System.out.println(0 + "," + compteur_rle);
				mode_rle = false;
				compteur_rle = 0;
			}

			// System.out.println("-----------------------------------------------");

			MatchLength len = null;
			MatchDistance dis = null;

			BinaryTree node_len = null;
			BinaryTree node_dis = null;
			//BinaryTree node_dico = null;

			// next, write contains of the "gen" vector into the output file
			for (Enumeration e = gen.elements(); e.hasMoreElements() ;) {
				len = (MatchLength) e.nextElement();
				// System.out.println("length = " + len.value);

				node_len = tabStatistics_len[len.value];
				/*
				if (len.value < 256) System.out.println((char) node_len.codeAscii + " ," + node_len.codeAscii
										+ " ," + Binary.toBinaryString(node_len.compressCode, node_len.nbBits)) ;
				*/
				// first, generating length parameter in all cases
				bos.writeBit(node_len.compressCode, node_len.nbBits);

				if ((len.value >= 257) && (len.value < len_max)) {

					// even, generating an extra length parameter
					if (len.nbExtraBits > 0) bos.writeBit(len.extraValue, len.nbExtraBits);

					// next, generating distance parameter
					dis = (MatchDistance) e.nextElement();
					// System.out.println("distance = " + dis.value);

					// the distance is a dictionnary entrie
					/*
					if (len.value == 280) {
						node_dico = Dico.tabStatistics_dico[dis.value];
						//System.out.println("dico = " + node_dico.word);
						bos.writeBit(node_dico.compressCode, node_dico.nbBits);
					}
					*/

					// the distance is a reference on the back window

					// BREAK_EVEN case
					if (len.value == 257) {
						bos.writeBit(dis.value, dis.nbBits);
					}

					// other cases
					else {
						node_dis = tabStatistics_dis[dis.value];
						bos.writeBit(node_dis.compressCode, node_dis.nbBits);
						// even, generating an extra distance parameter
						if (dis.nbExtraBits > 0) bos.writeBit(dis.extraValue, dis.nbExtraBits);
					}
				}
			}

			// writing a code for end of file
			node_len = tabStatistics_len[256];
			bos.writeBit(node_len.compressCode, node_len.nbBits);

			bos.writeEOF();

			bos.flush();
		 }
		 catch (Exception e) {
			 System.out.println("Compress " + e);
		 }
	}


	// ---------------------------------------------------------------------------------------------
	/**
		<p>find_match generate into the output file the code/group of bits associated to the string matching :</p>
		<li>generate a window string matching (length and distance in the binary tree representing the back window).</li>
		<li>generate a dictionnary string matching.</li>
		<li>generate a single ascii character.</li>
	*/
	public void find_match () {

		if (match_length > look_ahead_bytes)	match_length = look_ahead_bytes;

		if (match_length >= BREAK_EVEN) {
					//System.out.println("match_length = " + match_length);
					//System.out.println("match_distance = " + match_distance);

					if (match_length == 3) {
						tabStatistics_len[257].frequence ++;
						gen.addElement(new MatchLength(257,0,0));
						gen.addElement(new MatchDistance(match_distance-1,12));

						// printing the match string
						String word = "";
						for (int i=0; i < match_length; i++) {
							// if (window[mod_window(current_distance + i)] >= 256) word = word + Dico.tabStatistics_dico[(int) (window[mod_window(current_distance + i)]-256)].word;
							// else
							word = word + (char) window[mod_window(current_distance + i)];
						}
						System.out.println(word + "," + match_length + "," + match_distance);

						replace_count = match_length;
						return;
					}
					else if (match_length == 4) {
						tabStatistics_len[258].frequence ++;
						gen.addElement(new MatchLength(258,0,0));
					}
					else if (match_length == 5) {
						tabStatistics_len[259].frequence ++;
						gen.addElement(new MatchLength(259,0,0));
					}
					else if (match_length == 6) {
						tabStatistics_len[260].frequence ++;
						gen.addElement(new MatchLength(260,0,0));
					}
					else if (match_length == 7) {
						tabStatistics_len[261].frequence ++;
						gen.addElement(new MatchLength(261,0,0));
					}
					else if (match_length == 8) {
						tabStatistics_len[262].frequence ++;
						gen.addElement(new MatchLength(262,0,0));
					}
					else if (match_length == 9) {
						tabStatistics_len[263].frequence ++;
						gen.addElement(new MatchLength(263,0,0));
					}
					else if (match_length == 10) {
						tabStatistics_len[264].frequence ++;
						gen.addElement(new MatchLength(264,0,0));
					}
					else if ((match_length >= 11) && (match_length <= 12)) {
						tabStatistics_len[265].frequence ++;
						gen.addElement(new MatchLength(265,match_length-11,1));
					}
					else if ((match_length >= 13) && (match_length <= 14)) {
						tabStatistics_len[266].frequence ++;
						gen.addElement(new MatchLength(266,match_length-13,1));
					}
					else if ((match_length >= 15) && (match_length <= 16)) {
						tabStatistics_len[267].frequence ++;
						gen.addElement(new MatchLength(267,match_length-15,1));
					}
					else if ((match_length >= 17) && (match_length <= 18)) {
						tabStatistics_len[268].frequence ++;
						gen.addElement(new MatchLength(268,match_length-17,1));
					}
					else if ((match_length >= 19) && (match_length <= 22)) {
						tabStatistics_len[269].frequence ++;
						gen.addElement(new MatchLength(269,match_length-19,2));
					}
					else if ((match_length >= 23) && (match_length <= 26)) {
						tabStatistics_len[270].frequence ++;
						gen.addElement(new MatchLength(270,match_length-23,2));
					}
					else if ((match_length >= 27) && (match_length <= 30)) {
						tabStatistics_len[271].frequence ++;
						gen.addElement(new MatchLength(271,match_length-27,2));
					}
					else if ((match_length >= 31) && (match_length <= 34)) {
						tabStatistics_len[272].frequence ++;
						gen.addElement(new MatchLength(272,match_length-31,2));
					}
					else if ((match_length >= 35) && (match_length <= 42)) {
						tabStatistics_len[273].frequence ++;
						gen.addElement(new MatchLength(273,match_length-35,3));
					}
					else if ((match_length >= 43) && (match_length <= 50)) {
						tabStatistics_len[274].frequence ++;
						gen.addElement(new MatchLength(274,match_length-43,3));
					}

					else if ((match_length >= 51) && (match_length <= 58)) {
						tabStatistics_len[275].frequence ++;
						gen.addElement(new MatchLength(275,match_length-51,3));
					}
					else if ((match_length >= 59) && (match_length <= 66)) {
						tabStatistics_len[276].frequence ++;
						gen.addElement(new MatchLength(276,match_length-59,3));
					}
					else if ((match_length >= 67) && (match_length <= 98)) {
						tabStatistics_len[277].frequence ++;
						gen.addElement(new MatchLength(277,match_length-67,5));
					}
					else if ((match_length >= 99) && (match_length <= 130)) {
						tabStatistics_len[278].frequence ++;
						gen.addElement(new MatchLength(278,match_length-99,5));
					}
					else if ((match_length >= 131) && (match_length <= 257)) {
						tabStatistics_len[279].frequence ++;
						gen.addElement(new MatchLength(279,match_length-131,7));
					}
					else {
						System.out.println("match_length ? " + match_length);
					}

					if ((match_distance >= 1) && (match_distance <= 8)) {
						tabStatistics_dis[0].frequence ++;
						gen.addElement(new MatchDistance(0,match_distance,match_distance-1,3));
					}
					else if ((match_distance >= 9) && (match_distance <= 16)) {
						tabStatistics_dis[1].frequence ++;
						gen.addElement(new MatchDistance(1,match_distance,match_distance-9,3));
					}
					else if ((match_distance >= 17) && (match_distance <= 24)) {
						tabStatistics_dis[2].frequence ++;
						gen.addElement(new MatchDistance(2,match_distance,match_distance-17,3));
					}
					else if ((match_distance >= 25) && (match_distance <= 32)) {
						tabStatistics_dis[3].frequence ++;
						gen.addElement(new MatchDistance(3,match_distance,match_distance-25,3));
					}
					else if ((match_distance >= 33) && (match_distance <= 48)) {
						tabStatistics_dis[4].frequence ++;
						gen.addElement(new MatchDistance(4,match_distance,match_distance-33,4));
					}
					else if ((match_distance >= 49) && (match_distance <= 64)) {
						tabStatistics_dis[5].frequence ++;
						gen.addElement(new MatchDistance(5,match_distance,match_distance-49,4));
					}
					else if ((match_distance >= 65) && (match_distance <= 96)) {
						tabStatistics_dis[6].frequence ++;
						gen.addElement(new MatchDistance(6,match_distance,match_distance-65,5));
					}
					else if ((match_distance >= 97) && (match_distance <= 128)) {
						tabStatistics_dis[7].frequence ++;
						gen.addElement(new MatchDistance(7,match_distance,match_distance-97,5));
					}
					else if ((match_distance >= 129) && (match_distance <= 192)) {
						tabStatistics_dis[8].frequence ++;
						gen.addElement(new MatchDistance(8,match_distance,match_distance-129,6));
					}
					else if ((match_distance >= 193) && (match_distance <= 256)) {
						tabStatistics_dis[9].frequence ++;
						gen.addElement(new MatchDistance(9,match_distance,match_distance-193,6));
					}
					else if ((match_distance >= 257) && (match_distance <= 384)) {
						tabStatistics_dis[10].frequence ++;
						gen.addElement(new MatchDistance(10,match_distance,match_distance-257,7));
					}
					else if ((match_distance >= 385) && (match_distance <= 512)) {
						tabStatistics_dis[11].frequence ++;
						gen.addElement(new MatchDistance(11,match_distance,match_distance-385,7));
					}
					else if ((match_distance >= 513) && (match_distance <= 768)) {
						tabStatistics_dis[12].frequence ++;
						gen.addElement(new MatchDistance(12,match_distance,match_distance-513,8));
					}
					else if ((match_distance >= 769) && (match_distance <= 1024)) {
						tabStatistics_dis[13].frequence ++;
						gen.addElement(new MatchDistance(13,match_distance,match_distance-769,8));
					}
					else if ((match_distance >= 1025) && (match_distance <= 1536)) {
						tabStatistics_dis[14].frequence ++;
						gen.addElement(new MatchDistance(14,match_distance,match_distance-1025,9));
					}
					else if ((match_distance >= 1537) && (match_distance <= 2048)) {
						tabStatistics_dis[15].frequence ++;
						gen.addElement(new MatchDistance(15,match_distance,match_distance-1537,9));
					}
					else if ((match_distance >= 2049) && (match_distance <= 3072)) {
						tabStatistics_dis[16].frequence ++;
						gen.addElement(new MatchDistance(16,match_distance,match_distance-2049,10));
					}
					else if ((match_distance >= 3073) && (match_distance <= 4096)) {
						tabStatistics_dis[17].frequence ++;
						gen.addElement(new MatchDistance(17,match_distance,match_distance-3073,10));
					}
					else if ((match_distance >= 4097) && (match_distance <= 6144)) {
						tabStatistics_dis[18].frequence ++;
						gen.addElement(new MatchDistance(18,match_distance,match_distance-4097,11));
					}
					else if ((match_distance >= 6145) && (match_distance <= 8192)) {
						tabStatistics_dis[19].frequence ++;
						gen.addElement(new MatchDistance(19,match_distance,match_distance-6145,11));
					}

					else if ((match_distance >= 8193) && (match_distance <= 12288)) {
						tabStatistics_dis[20].frequence ++;
						gen.addElement(new MatchDistance(20,match_distance,match_distance-8193,12));
					}
					else if ((match_distance >= 12289) && (match_distance <= 16384)) {
						tabStatistics_dis[21].frequence ++;
						gen.addElement(new MatchDistance(21,match_distance,match_distance-12289,12));
					}
					else if ((match_distance >= 16385) && (match_distance <= 20480)) {
						tabStatistics_dis[22].frequence ++;
						gen.addElement(new MatchDistance(22,match_distance,match_distance-16385,12));
					}
					else if ((match_distance >= 20481) && (match_distance <= 24576)) {
						tabStatistics_dis[23].frequence ++;
						gen.addElement(new MatchDistance(23,match_distance,match_distance-20481,12));
					}
					else if ((match_distance >= 24577) && (match_distance <= 28672)) {
						tabStatistics_dis[24].frequence ++;
						gen.addElement(new MatchDistance(24,match_distance,match_distance-24577,12));
					}
					else if ((match_distance >= 28673) && (match_distance <= 32768)) {
						tabStatistics_dis[25].frequence ++;
						gen.addElement(new MatchDistance(25,match_distance,match_distance-28673,12));
					}

					else if ((match_distance >= 32769) && (match_distance <= 36864)) {
						tabStatistics_dis[26].frequence ++;
						gen.addElement(new MatchDistance(26,-1,match_distance-32769,12));
					}
					else if ((match_distance >= 36865) && (match_distance <= 40960)) {
						tabStatistics_dis[27].frequence ++;
						gen.addElement(new MatchDistance(27,-1,match_distance-36865,12));
					}
					else if ((match_distance >= 40961) && (match_distance <= 45056)) {
						tabStatistics_dis[28].frequence ++;
						gen.addElement(new MatchDistance(28,-1,match_distance-40961,12));
					}
					else if ((match_distance >= 45057) && (match_distance <= 49152)) {
						tabStatistics_dis[29].frequence ++;
						gen.addElement(new MatchDistance(29,-1,match_distance-45057,12));
					}
					else if ((match_distance >= 49153) && (match_distance <= 57344)) {
						tabStatistics_dis[30].frequence ++;
						gen.addElement(new MatchDistance(30,-1,match_distance-49153,13));
					}
					else if ((match_distance >= 57345) && (match_distance <= 65536)) {
						tabStatistics_dis[31].frequence ++;
						gen.addElement(new MatchDistance(31,-1,match_distance-57345,13));
					}
					else {
						System.out.println("match_distance ? " + match_distance);
					}

					// printing the match string
					String word = "";
					for (int i=0; i < match_length; i++) {
						// if (window[mod_window(current_distance + i)] >= 256) word = word + Dico.tabStatistics_dico[(int) (window[mod_window(current_distance + i)]-256)].word;
						// else
						word = word + (char) window[mod_window(current_distance + i)];
					}
					System.out.println(word + "," + match_length + "," + match_distance);

					replace_count = match_length;
					return;
		}

		replace_count = 1;
		//System.out.println("current_distance = " + current_distance);

		int codeAscii = window[current_distance];
		//System.out.println("codeAscii = " + codeAscii);

		// generate a single ascii character.
		tabStatistics_len[codeAscii].frequence ++;
		gen.addElement(new MatchLength(codeAscii));
		System.out.println((char) window[current_distance] + "," + codeAscii);

	}


	// ---------------------------------------------------------------------------------------------
	private int mod_window(int a)
	{
		return (int) (a & (WINDOW_SIZE - 1));
	}


	// ---------------------------------------------------------------------------------------------
	/**
		<p>Search for a string matching in the tree (on the back window).</p>
	*/
	private void addString(int new_node)
	{
		int test_node = tree_larger_child[ TREE_ROOT ];
		int delta = 0;
		int child;
		int i = 0;

		match_length = 0;
		match_distance = 0;
		// match_node = 0;

		while (true) {

			// delta = window[ mod_window(new_node + match_length-1) ] - window[ mod_window(test_node + match_length-1) ];
			// int j = 0;

			// variable "i" will give the length of the string matching.
			for (i=0; i < LOOK_AHEAD_SIZE; i++) {
				delta = window[ mod_window(new_node + i) ] - window[ mod_window(test_node + i) ];
				if (delta != 0) break;
				/*
				if (window[ mod_window(test_node + i) ] >= 256) {
					int ref = window[ mod_window(test_node + i) ] - 256;
					j = j + tabStatistics_dico[ref].word.length();
				}
				else j++;
				*/
			}

			// if i is BREAK_EVEN only near string matching are accepted (in the last 4096 bytes).
			if ((i == BREAK_EVEN) && (mod_window(current_distance - test_node) > 4096)) i = 0;

			// if i is greater than previous match_length, save the match_length and match_distance variables of the string matching.
			if ((i >= BREAK_EVEN) && (i >= match_length)) {
				match_length = i;
				match_distance = mod_window(current_distance - test_node);
				// match_node = j;
				if (match_length >= LOOK_AHEAD_SIZE) {
					replaceNode(test_node, new_node);
					return;
				}
			}

			// searching into the huffman tree (on the back window) for a new string matching.
			if (delta >= 0)	{
				child = tree_larger_child[ test_node ];

				if (child == UNUSED) {
					tree_larger_child[ test_node ] = new_node;
					tree_parent[ new_node ] = test_node;
					tree_larger_child[ new_node ] = UNUSED;
					tree_smaller_child[ new_node ] = UNUSED;
					return;
				}
			}
			else	{
				child = tree_smaller_child[ test_node ];

				if (child == UNUSED) {
					tree_smaller_child[ test_node ] = new_node;
					tree_parent[ new_node ] = test_node;
					tree_larger_child[ new_node ] = UNUSED;
					tree_smaller_child[ new_node ] = UNUSED;
					return;
				}
			}

			test_node = child;
		}
	}


	// ---------------------------------------------------------------------------------------------
	/**
		<p>Search for a string matching in the tree (on the back window).</p>
		<p>For lazy matching only.</p>
	*/
	private void searchString(int new_node)
	{
		int test_node = tree_larger_child[ TREE_ROOT ];
		int delta = 0;
		int child;
		int i;

		match_length = 0;
		match_distance = 0;
		// match_node = 0;

		while (true) {

			// delta = window[ mod_window(new_node + match_length-1) ] - window[ mod_window(test_node + match_length-1) ];
			// int j = 0;

			for (i=0; i < LOOK_AHEAD_SIZE; i++) {
				delta = window[ mod_window(new_node + i) ] - window[ mod_window(test_node + i) ];
				if (delta != 0) break;
				/*
				if (window[ mod_window(test_node + i) ] >= 256) {
					int ref = window[ mod_window(test_node + i) ] - 256;
					j = j + tabStatistics_dico[ref].word.length();
				}
				else j++;
				*/
			}

			if ((i == BREAK_EVEN) && (mod_window(current_distance - test_node) > 4096)) i = 0;

			if ((i >= BREAK_EVEN) && (i >= match_length)) {
				match_length = i;
				match_distance = mod_window(current_distance - test_node);
				// match_node = j;
				if (match_length >= LOOK_AHEAD_SIZE) {
					return;
				}
			}

			if (delta >= 0)	{
				child = tree_larger_child[ test_node ];

				if (child == UNUSED) {
					return;
				}
			}
			else	{
				child = tree_smaller_child[ test_node ];

				if (child == UNUSED) {
					return;
				}
			}

			test_node = child;
		}
	}

	// ---------------------------------------------------------------------------------------------
	private void deleteString(int p)
	{
		int replacement;

		if (tree_parent[ p ] == UNUSED)	return;

		if (tree_larger_child[ p ] == UNUSED)	contractNode(p, tree_smaller_child[ p ]);
		else if (tree_smaller_child[ p ] == UNUSED)	contractNode(p, tree_larger_child[ p ]);
		else {
				replacement = findNextNode(p);
				deleteString(replacement);
				replaceNode(p, replacement);
		}
	}


	// ---------------------------------------------------------------------------------------------
	private void contractNode(int old_node, int new_node)
	{
		tree_parent[ new_node ] = tree_parent[ old_node ];

		if (tree_larger_child[ tree_parent[ old_node ] ] == old_node) {
			tree_larger_child[ tree_parent[ old_node ] ] = new_node;
		}
		else {
			tree_smaller_child[ tree_parent[ old_node ] ] = new_node;
		}

		tree_parent[ old_node ] = UNUSED;
	}


	// ---------------------------------------------------------------------------------------------
	private void replaceNode(int old_node, int new_node)
	{
		int parent = tree_parent[ old_node ];

		if (tree_smaller_child[ parent ] == old_node) {
			tree_smaller_child[ parent ] = new_node;
		}
		else {
			tree_larger_child[ parent ] = new_node;
		}

		tree_parent[ new_node ] = tree_parent[ old_node ];
		tree_smaller_child[ new_node ] = tree_smaller_child [ old_node ];
		tree_larger_child[ new_node ] = tree_larger_child [ old_node ];

		tree_parent[ tree_smaller_child[ new_node ] ] = new_node;
		tree_parent[ tree_larger_child[ new_node ] ] = new_node;
		tree_parent[ old_node ] = UNUSED;
	}


	// ---------------------------------------------------------------------------------------------
	private int findNextNode(int node)
	{
		int next = tree_smaller_child[ node ];

		while (tree_larger_child[ next ] != UNUSED) {
			next = tree_larger_child[ next ];
		}

		return next;
	}


	/**
		<p>Reading the next character from the input file.</p>
		<p>Here, an upper to lower case transformation code is operated on the base text using a escape context code.</p>
	*/
	// ---------------------------------------------------------------------------------------------
	private int readNext() throws EOFException {
		try {
			return (bis.readByte() + 128);

		}
		catch (Exception e) {
			throw new EOFException();
		}
	}

	// ---------------------------------------------------------------------------------
	public int reverse(int value, int size) {
		int result = 0x00;
		for (int i=0; i<size; i++) {
			result = (result << 1 ) | ((value >> i) & 0x01);
		}
		return result;
	}

	// ---------------------------------------------------------------------------------
	public void convert_tree_to_code(BinaryTree tree,
								 	int nbBits)
	{
		if (tree.isLeafNode()) {
			tree.nbBits = nbBits;
			// tree.toString();
			count[nbBits]++;
			return;
		}

		nbBits ++;

		convert_tree_to_code(tree.node_0, nbBits);
		convert_tree_to_code(tree.node_1, nbBits);
	}


	// -----------------------------------------------------------------------------
	static public void sort(Vector list, int low, int high)
	{
		if (!list.isEmpty()) {
			BinaryTree previous = null;
			BinaryTree current = null;

			int l = low;
			int h = high;
			int pivot = ((BinaryTree) list.elementAt((low + high) / 2)).frequence;

			while (l <= h) {
				while (((BinaryTree) list.elementAt(l)).frequence < pivot) l++;
				while (pivot < ((BinaryTree) list.elementAt(h)).frequence) h--;

				if (l <= h) {

					previous = (BinaryTree) list.elementAt(l);
					current  = (BinaryTree) list.elementAt(h);

					list.removeElementAt(l);
					list.insertElementAt(current,l);

					list.removeElementAt(h);
					list.insertElementAt(previous,h);

					l++;
					h--;
				}
			}

			// Sort the low part of the list :
			if (low < h) sort(list, low, h);

			// Sort the high part of the list :
			if (l < high) sort(list, l, high);
		}
	}


	// ---------------------------------------------------------------------------------------------
	public void print(BinaryTree[] tab) {
		int size = 0;
		for (int i=0; i< tab.length; i++) {
			BinaryTree node = tab[i];
			if ((node != null) && (node.frequence > 0)) {
				size ++;
				if (node.word != null) System.out.print(node.word + " ");
				node.toString();
			}
		}
		System.out.println("array size is " +  size);
	}

	// ---------------------------------------------------------------------------------------------
	synchronized public void redirectOutput() {
		try {
			if (logFile==null) return;

			java.io.File oldFile = new java.io.File(logFile + ".old");
			java.io.File newFile = new java.io.File(logFile);

			if (oldFile.exists())	oldFile.delete();

			if (newFile.exists())	newFile.renameTo(oldFile);

			PrintStream p = new PrintStream(new FileOutputStream(newFile),true);

			System.setOut(p);
		}
		catch (Exception e) {
			System.out.println("redirectOutput " + e);
		}
	}

	static public void help()
	{
		System.out.println("LZCompress v1.0, Ronan Merien, rmerien@hotmail.com");
		System.out.println("Usage: java LZCompress fileName");
		System.out.println("Examples:	java LZCompress photo.bmp");
		System.out.println("		compress photo.bmp to photo.bmp.lz");
		System.out.println("");
	}

	// ---------------------------------------------------------------------------------------------

	/**
		<p>it operate the compression from the input file.</p>
	*/
	static public void main(String args[])
	{
		// java.util.Date d1 = new java.util.Date();

		int argc = args.length;

		if (argc > 0) {
			String filename = args[0];

			String inFile = filename;
			String outFile = filename + ".lz";

			try {
				FileOutputStream fos = new FileOutputStream(outFile);
				BufferedOutputStream bos = new BufferedOutputStream(fos);

				System.out.println("LZ compress " + inFile + " to " + outFile);

				LZCompress compress = new LZCompress(inFile, bos);
				bos.close();
			}
			catch (Exception e) {
				System.out.println(e);
			}
		}
		else help();

		/*
		java.util.Date d2 = new java.util.Date();
		java.util.Date d = new java.util.Date(d2.getTime() - d1.getTime());
		System.out.println("total time = " + d.getTime());
		*/
	}
}