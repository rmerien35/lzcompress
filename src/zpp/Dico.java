package zpp;

/*
	Zip++ application
	Copyright 2000, 2003 Zip-Technology.com

	@Author Ronan Merien <ronan.merien@zip-technology.com>
	@Version 1.2, 2003/10/15
*/

import java.util.zip.*;
import java.util.*;
import java.io.*;
import java.net.*;
import binary.*;

/**
	<p>Loading a static dictionnary.</p>

	<p>The dictionnary has been previously build using statistic informations :</p>
	<li>we analysed several english text, html pages and source code (as .java, .c, etc ...)</li>
	<li>each word and syllabe were associated with a frequence information (number of bits to code it)</li>
	<li>so the dictionnary was compressed by the huffman method.</li>

	<p>Two binary trees will be build for expand and compress purpose.<p>
*/

public class Dico {

	public static int dico_max = 8192;
	public static BinaryTree[] tabStatistics_dico = new BinaryTree[dico_max];
	public static BinaryTree tree_dico_compress = new BinaryTree();
	public static BinaryTree tree_dico_expand = new BinaryTree();

	static BinaryTree[] tabStatistics_len;

	static int len_max = 26;

	static int maxBits = 19;
	static int[] count = new int[maxBits];
	static int[] next_code = new int[maxBits];

	protected PrintWriter out;

	static String dicoFileName = "english.dico";
	static String jarFileName = null;

	public Dico () {
	}

	public static void load_dico(String _jarFileName, String _dicoFileName)
	{
		jarFileName = _jarFileName;
		dicoFileName = _dicoFileName;
		load_dico();
	}

	public static void load_dico()
	{
		try {

			for (int i=0; i < maxBits; i++) {
				count[i] = 0;
				next_code[i] = 0;
			}

			BinaryInputStream bis;

			if (jarFileName != null) {


				URL newURL = new URL(jarFileName);
				ZipInputStream zis = new ZipInputStream(newURL.openConnection().getInputStream());

				ZipEntry ze;
				while ( ((ze = zis.getNextEntry())!= null)
					&& (!ze.getName().equals(dicoFileName)) ) {
				}

				int size = 21654;
				byte[] buffer = new byte[size];

				final int N = 1024;
				byte[] temp = new byte[N];
				int ln = 0, diff = size;
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				while (diff > 0 && (ln = zis.read(temp, 0, Math.min(N, size))) != -1) {
					baos.write(temp,0,ln);
					diff -= ln;
				}
				buffer = baos.toByteArray();
				baos.close();
				zis.close();

				bis = new BinaryInputStream(new ByteArrayInputStream(buffer));
			}
			else {
				bis = new BinaryInputStream(new FileInputStream(dicoFileName));
			}

			try {
				String str = "";
				int frequence = 0;
				int word_count = 0;
				int word_length = 0;
				int word_max = 0;

				tabStatistics_len = new BinaryTree[len_max];
				for (int i=0; i<len_max; i++) {
					tabStatistics_len[i] = new BinaryTree(i,0);
				}

				bis.readBit(8);
				word_max = bis.readBit(16);

				for (int i=0; i<len_max; i++) {
					int value = bis.readBit(4);
					tabStatistics_len[i].nbBits = value;
					count[value]++;
					// System.out.println(i + "> " + value);
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
						convert_code_to_tree_compress(tree_len, tabStatistics_len[i], len);
						next_code[len]++;
					}
				}

				for (int i=0; i < maxBits; i++) {
					count[i] = 0;
					next_code[i] = 0;
				}


				for (int i=0; i<word_max; i++)
				{
					word_length = (int) bis.readBit(5);
					// System.out.println("word_length" + "> " + word_length);
					frequence = (int) bis.readBit(5);
					// System.out.println("frequence" + "> " + frequence);

					for (int j=0; j < word_length; j++)
					{
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

						str = str + (char) (value + 0x61);
					}

					// System.out.println("str = " + str);

					tabStatistics_dico[word_count] = new BinaryTree(word_count, str, 0);
					tabStatistics_dico[word_count].nbBits = frequence;
					count[frequence]++;

					word_length = 0;
					str = "";
					frequence = 0;
					word_count ++;

				}

			}
			catch (EOFException e) {
				// System.out.println(e);
			}

			bis.close();

		}
		catch (Exception e) {
			System.out.println("load_dico " + e);
		}

		// ------------- building the tree_dico_compress ---------------
		// ------------- building the tree_dico_expand -----------------

		int compressCode = 0;
		count[0] = 0;
		for (int bits = 1; bits < maxBits; bits++) {
			compressCode = (compressCode + count[bits-1]) << 1;
			next_code[bits] = compressCode;
		}

		BinaryTree node = null;

		for (int i=0; i<dico_max; i++)
		{
			node = tabStatistics_dico[i];

			if (node != null) {
				int len = node.nbBits;
				if (len != 0) {
					node.compressCode = reverse(next_code[len],len);
					convert_code_to_tree_compress(tree_dico_compress, tabStatistics_dico[i], len);
					next_code[len]++;
				}
			}
		}

		int expandCode = 0;
		count[0] = 0;
		for (int bits = 1; bits < maxBits; bits++) {
			expandCode = (expandCode + count[bits-1]) << 1;
			next_code[bits] = expandCode;
		}

		for (int i=0; i<dico_max; i++)
		{
			node = tabStatistics_dico[i];

			if (node != null) {
				int len = node.nbBits;
				if (len != 0) {
					node.expandCode = next_code[len];
					convert_code_to_tree_expand(tree_dico_expand, tabStatistics_dico[i], len);
					next_code[len]++;
				}
			}
		}

		// print(tabStatistics_dico);
		for (int i=0; i<dico_max; i++) {
			node = tabStatistics_dico[i];

			if (node != null) {
				// System.out.println("i = " + i + ", " + "node = " + node.word);
				convert_word_to_tree(tree_dico_compress, node, node.word.length());
			}
		}
	}


	// ---------------------------------------------------------------------------------------------
	static public int reverse(int value, int size) {
		int result = 0x00;
		for (int i=0; i<size; i++) {
			result = (result << 1 ) | ((value >> i) & 0x01);
		}
		return result;
	}

	// ---------------------------------------------------------------------------------------------
	static public void convert_tree_to_code(BinaryTree tree, int nbBits) {
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


	// ---------------------------------------------------------------------------------------------
	static public BinaryTree convert_code_to_tree_compress(BinaryTree tree,
															BinaryTree node,
															int nbBits) {
		if (nbBits == 0) {
			return node;
		}

		if (tree == null) tree = new BinaryTree(-1,0);

		int currentBit = ( (node.compressCode >> (nbBits - 1)) & 0x01 );
		nbBits --;

		if (currentBit == 0x00)	tree.node_0 = convert_code_to_tree_compress(tree.node_0, node, nbBits);
		else					tree.node_1 = convert_code_to_tree_compress(tree.node_1, node, nbBits);

		return tree;
	}

	// ---------------------------------------------------------------------------------------------
	static public BinaryTree convert_code_to_tree_expand(BinaryTree tree,
														BinaryTree node,
														int nbBits) {
		if (nbBits == 0) {
			return node;
		}

		if (tree == null) tree = new BinaryTree(-1,0);

		int currentBit = ( (node.expandCode >> (nbBits - 1)) & 0x01 );
		nbBits --;

		if (currentBit == 0x00)	tree.node_0 = convert_code_to_tree_expand(tree.node_0, node, nbBits);
		else					tree.node_1 = convert_code_to_tree_expand(tree.node_1, node, nbBits);

		return tree;
	}

	// ---------------------------------------------------------------------------------------------
	static public void convert_word_to_tree(BinaryTree tree,
											BinaryTree node,
											int len)
	{
		int i = -1;
		while (len != 0) {
			i = ((int) node.word.charAt(node.word.length() - len)) - 0x61;
			len --;

			if (len != 0) {
				if (tree.tab[i] == null) tree.tab[i] = new BinaryTree();
				tree = tree.tab[i];
			}
		}

		if (tree.tab[i] == null) tree.tab[i] = new BinaryTree();
		tree.tab[i].word = node.word;
		tree.tab[i].codeAscii = node.codeAscii;
		tree.tab[i].compressCode = node.compressCode;
		tree.tab[i].expandCode = node.expandCode;
		tree.tab[i].nbBits = node.nbBits;

		// tree.tab[i] = node;
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
	static public void print(BinaryTree[] tab)
	{
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

}
