
/*
	Zip++ application
	Copyright 2000, 2003 Zip-Technology.com

	@Author Ronan Merien <ronan.merien@zip-technology.com>
	@Version 1.2, 2003/10/15
*/

import java.io.*;
import java.util.*;
import binary.*;
import zpp.*;

/**
	<p>This class deflate a previous compress file.</p>

	<p>The compression used a combination of the LZSS algorithm, Huffman coding and word references from a static dictionnary.</p>
	<p>The LZSS algorithm may use a reference to a duplicated string (256 bytes max) occurring in a previous block, up to 64K input bytes before.</p>

	<p>The compressed data consists of a series of elements of two types: literal bytes (of strings that have not been detected
	as duplicated within the previous input bytes), and pointers to duplicated strings, where a pointer is represented as a pair <length, backward distance>.</p>

	<p>Each type of value (literals, distances, and lengths) in the compressed data is represented
	using a Huffman code, using one code tree for literals and lengths and a separate code tree for distances.</p>
	<p>The code trees appear in a compact form just before the compressed data.</p>
*/
public class LZExpand {

	String logFile = "log.txt";

	static int INDEX_BIT_COUNT = 16;
	static int LENGTH_BIT_COUNT = 8;
	static int WINDOW_SIZE = ( 1 << INDEX_BIT_COUNT); // 65536;
	static int LOOK_AHEAD_SIZE = ( 1 << LENGTH_BIT_COUNT); // 256
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

	int[] window;

	int current_distance = 1;
	int match_length = 0;
	int match_distance = 0;

	public LZExpand (String outFile, InputStream is)
	{
		if (logFile != null) redirectOutput();

		window = new int[WINDOW_SIZE];

		// Get statistics from input stream
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

		try {

			// ----------------------------------------------------------------------------------------------------------------------

			bis = new BinaryInputStream(new BufferedInputStream(is));

			FileOutputStream fos = new FileOutputStream(outFile);
			BinaryOutputStream bos = new BinaryOutputStream(new BufferedOutputStream(fos));

			try {

				// get statistics from input stream
				// --------------------------------------------------------

				for (int i=0; i < maxBits; i++) {
					count[i] = 0;
					next_code[i] = 0;
				}

				for (int i=0; i<len_max; i++) {
					int value = bis.readBit(4);

					if (value == 0x00){
						i = i + bis.readBit(8);
					}
					else {
						if (value == 15) value = value + bis.readBit(2);
						if (value == 18) value = value + bis.readBit(2);
						tabStatistics_len[i].nbBits = value;
						count[value]++;
					}
				}

				int code = 0;
				count[0] = 0;
				for (int bits = 1; bits < maxBits; bits++) {
					code = (code + count[bits-1]) << 1;
					next_code[bits] = code;
				}

				BinaryTree tree_len = new BinaryTree(-1,0);

				for (int i=0; i<len_max; i++) {
					int len = tabStatistics_len[i].nbBits;
					if (len != 0) {
						tabStatistics_len[i].compressCode = next_code[len];
						convert_code_to_tree(tree_len, tabStatistics_len[i], len);
						next_code[len]++;
					}
				}

				// --------------------------------------------------------

				for (int i=0; i< maxBits; i++) {
					count[i] = 0;
					next_code[i] = 0;
				}

				for (int i=0; i<dis_max; i++) {
					int value = bis.readBit(4);

					if (value == 0x00) {
						i = i + bis.readBit(5);
					}
					else {
						tabStatistics_dis[i].nbBits = value;
						count[value]++;
					}
				}

				code = 0;
				count[0] = 0;
				for (int bits = 1; bits < maxBits; bits++) {
					code = (code + count[bits-1]) << 1;
					next_code[bits] = code;
				}

				BinaryTree tree_dis = new BinaryTree(-1,0);

				for (int i=0; i<dis_max; i++) {
					int len = tabStatistics_dis[i].nbBits;
					if (len != 0) {
						tabStatistics_dis[i].compressCode = next_code[len];
						convert_code_to_tree(tree_dis, tabStatistics_dis[i], len);
						next_code[len]++;
					}
				}


				System.out.println("-----------------------------------------------");
				for (int i=0; i<len_max; i++) {
				System.out.println(Binary.toHexaString((byte)i)
				+ " -> "
				+ tabStatistics_len[i].nbBits);
				}

				System.out.println("-----------------------------------------------");
				for (int i=0; i<dis_max; i++) {
					System.out.println(Binary.toHexaString((byte)i)
					+ " -> "
					+ tabStatistics_dis[i].nbBits);
				}

				System.out.println("-----------------------------------------------");
				for (int i=0; i<len_max; i++) {
					System.out.println(Binary.toHexaString((byte)i)
					+ " -> "
					+ Binary.toBinaryString(tabStatistics_len[i].compressCode,tabStatistics_len[i].nbBits));
				}

				System.out.println("-----------------------------------------------");
				for (int i=0; i<dis_max; i++) {
					System.out.println(Binary.toHexaString((byte)i)
					+ " -> "
					+ Binary.toBinaryString(tabStatistics_dis[i].compressCode,tabStatistics_dis[i].nbBits));
				}

				System.out.println("-----------------------------------------------");


				// expand until the end of the input file
				while (true) {
					match_length = 0;
					match_distance = 0;

					BinaryTree node = null;
					boolean find = false;
					int value = -1;
					byte b = 0;

					node = tree_len;
					while (!find) {
						b = bis.readBit();
						// System.out.print(Binary.toSingleBinaryString(b));

						if (b == 0x00)	node = node.node_0;
						else			node = node.node_1;

						if (node == null) throw new Exception();

						value = node.codeAscii;
						if (value != -1) find = true;
					}

					// System.out.println();
					System.out.println("value = " + value);

					// single character
					if (value < 256) {
						int code_ascii = generate(value);

						System.out.println((char) code_ascii + "," + (int) code_ascii);
						bos.writeByte((byte) code_ascii);

						window[current_distance] = value;
						current_distance = mod_window(current_distance + 1);

					}
					// end of file
					else if (value == 256) break;
					// back window reference
					else if ((value >= 257) && (value <= 279)) {

						if (value == 257) {
							match_length = BREAK_EVEN;
							match_distance =  bis.readBit(12)+1;
						}
						else {
							if (value == 258) {
								match_length = 4;
							}
							else if (value == 259) {
								match_length = 5;
							}
							else if (value == 260) {
								match_length = 6;
							}
							else if (value == 261) {
								match_length = 7;
							}
							else if (value == 262) {
								match_length = 8;
							}
							else if (value == 263) {
								match_length = 9;
							}
							else if (value == 264) {
								match_length = 10;
							}
							else if (value == 265) {
								match_length = 11 + bis.readBit(1);
							}
							else if (value == 266) {
								match_length = 13 + bis.readBit(1);
							}
							else if (value == 267) {
								match_length = 15 + bis.readBit(1);
							}
							else if (value == 268) {
								match_length = 17 + bis.readBit(1);
							}
							else if (value == 269) {
								match_length = 19 + bis.readBit(2);
							}
							else if (value == 270) {
								match_length = 23 + bis.readBit(2);
							}
							else if (value == 271) {
								match_length = 27 + bis.readBit(2);
							}
							else if (value == 272) {
								match_length = 31 + bis.readBit(2);
							}
							else if (value == 273) {
								match_length = 35 + bis.readBit(3);
							}
							else if (value == 274) {
								match_length = 43 + bis.readBit(3);
							}
							else if (value == 275) {
								match_length = 51 + bis.readBit(3);
							}
							else if (value == 276) {
								match_length = 59 + bis.readBit(3);
							}
							else if (value == 277) {
								match_length = 67 + bis.readBit(5);
							}
							else if (value == 278) {
								match_length = 99 + bis.readBit(5);
							}
							else if (value == 279) {
								match_length = 131 + bis.readBit(7);
							}

							node = tree_dis;
							find = false;
							value = -1;

							while (!find) {
								b = bis.readBit();
								// System.out.print(Binary.toSingleBinaryString(b));

								if (b == 0x00)	node = node.node_0;
								else 			node = node.node_1;

								if (node == null) throw new Exception();

								value = node.codeAscii;
								if (value != -1) find = true;
							}
							// System.out.println();
							// System.out.println("value = " + value);

							if (value == 0) {
								match_distance = 1 + bis.readBit(3);
							}
							else if (value == 1) {
								match_distance = 9 + bis.readBit(3);
							}
							else if (value == 2) {
								match_distance = 17 + bis.readBit(3);
							}
							else if (value == 3) {
								match_distance = 25 + bis.readBit(3);
							}
							else if (value == 4) {
								match_distance = 33 + bis.readBit(4);
							}
							else if (value == 5) {
								match_distance = 49 + bis.readBit(4);
							}
							else if (value == 6) {
								match_distance = 65 + bis.readBit(5);
							}
							else if (value == 7) {
								match_distance = 97 + bis.readBit(5);
							}
							else if (value == 8) {
								match_distance= 129 + bis.readBit(6);
							}
							else if (value == 9) {
								match_distance = 193 + bis.readBit(6);
							}
							else if (value == 10) {
								match_distance = 257 + bis.readBit(7);
							}
							else if (value == 11) {
								match_distance = 385 + bis.readBit(7);
							}
							else if (value == 12) {
								match_distance = 513 + bis.readBit(8);
							}
							else if (value == 13) {
								match_distance = 769 + bis.readBit(8);
							}
							else if (value == 14) {
								match_distance = 1025 + bis.readBit(9);
							}
							else if (value == 15) {
								match_distance = 1537 + bis.readBit(9);
							}
							else if (value == 16) {
								match_distance = 2049 + bis.readBit(10);
							}
							else if (value == 17) {
								match_distance = 3073 + bis.readBit(10);
							}
							else if (value == 18) {
								match_distance = 4097 + bis.readBit(11);
							}
							else if (value == 19) {
								match_distance = 6145 + bis.readBit(11);
							}
							else if (value == 20) {
								match_distance = 8193 + bis.readBit(12);
							}
							else if (value == 21) {
								match_distance = 12289 + bis.readBit(12);
							}
							else if (value == 22) {
								match_distance = 16385 + bis.readBit(12);
							}
							else if (value == 23) {
								match_distance = 20481 + bis.readBit(12);
							}
							else if (value == 24) {
								match_distance = 24577 + bis.readBit(12);
							}
							else if (value == 25) {
								match_distance = 28673 + bis.readBit(12);
							}
							else if (value == 26) {
								match_distance = 32769 + bis.readBit(12);
							}
							else if (value == 27) {
								match_distance = 36865 + bis.readBit(12);
							}
							else if (value == 28) {
								match_distance = 40961 + bis.readBit(12);
							}
							else if (value == 29) {
								match_distance = 45057 + bis.readBit(12);
							}
							else if (value == 30) {
								match_distance = 49153 + bis.readBit(13);
							}
							else if (value == 31) {
								match_distance = 57345 + bis.readBit(13);
							}

						} // End If

						// System.out.println("match_length = " + match_length);
						// System.out.println("match_distance = " + match_distance);

						for (int i=0; i< match_length; i++) {
							value = window[mod_window(current_distance - match_distance)];
							// System.out.println("value = " + value);

							if (value >= 256) {
								int delta = value - 256;

								for (int j=0; j< Dico.tabStatistics_dico[delta].word.length(); j++) {
									int code_ascii = generate(Dico.tabStatistics_dico[delta].word.charAt(j));

									System.out.println((char) code_ascii + "," + (int) code_ascii);
									bos.writeByte((byte) code_ascii);
								}
							}
							else {
								int code_ascii = generate(value);

								System.out.println((char) code_ascii + "," + (int) code_ascii);
								bos.writeByte((byte) code_ascii);

							}

							window[current_distance] = value;
							current_distance = mod_window(current_distance + 1);
						}

					} // End If
				} // End While

			}
			catch (EOFException e) {
				System.out.println(e);
			}

			bis.close();
			bos.close();
		}
		catch (Exception e) {
			// Not a Zip++ File
			System.out.println(e);
		}
	}

	// ---------------------------------------------------------------------------------------------
	private int mod_window(int a)
	{
		return (int) (a & (WINDOW_SIZE - 1));
	}


	// ---------------------------------------------------------------------------------------------
	/**
	*/
	private int generate(int code_ascii) {

		return code_ascii - 128;
	}

	// ---------------------------------------------------------------------------------------------
	public BinaryTree convert_code_to_tree(BinaryTree tree,
									 		BinaryTree node,
									 		int nbBits)
	{
		if (nbBits == 0) {
			return node;
		}

		if (tree == null) tree = new BinaryTree(-1,0);

		int currentBit = ( (node.compressCode >> (nbBits - 1)) & 0x01 );
		nbBits --;

		if (currentBit == 0x00)	tree.node_0 = convert_code_to_tree(tree.node_0, node, nbBits);
		else 					tree.node_1 = convert_code_to_tree(tree.node_1, node, nbBits);

		return tree;
	}

	// ---------------------------------------------------------------------------------------------
	synchronized public void redirectOutput()
	{
		try {
			if (logFile==null) return;

			java.io.File oldFile = new java.io.File(logFile + ".old");
			java.io.File newFile = new java.io.File(logFile);

			if (oldFile.exists())
					oldFile.delete();

			if (newFile.exists())
			newFile.renameTo(oldFile);

			PrintStream p = new PrintStream(new FileOutputStream(newFile),true);

			System.setOut(p);
		}
		catch (Exception e) {
			System.out.println(e);
		}
	}

	// ---------------------------------------------------------------------------------------------

	static public void help()
	{
		System.out.println("LZExpand v1.0, Ronan Merien, rmerien@hotmail.com");
		System.out.println("Usage: java LZExpand fileName");
		System.out.println("Examples:	java LZExpand photo.bmp.lz");
		System.out.println("		expand photo.bmp.lz to photo.bmp");
		System.out.println("");
	}

	// ---------------------------------------------------------------------------------------------

	/**
		<p>it expand the ".lz" input file.</p>
	*/
	static public void main(String args[]) {
		// java.util.Date d1 = new java.util.Date();

		int argc = args.length;
		if (argc > 0) {
			String filename = args[0];
			String extention = "";

			while (filename.indexOf(".") != -1) {
				StringTokenizer f = new StringTokenizer(filename,".");
				filename = f.nextToken();
				extention = f.nextToken();
			}

			try {
				String inFile = filename + "." + extention + ".lz";
				String outFile = filename + "_copy." + extention;

				FileInputStream fis = new FileInputStream(inFile);
				BufferedInputStream bis = new BufferedInputStream(fis);

				System.out.println("LZ expand " + inFile + " to " + outFile);

				LZExpand expand = new LZExpand(outFile, bis);
				bis.close();
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