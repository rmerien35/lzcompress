package binary;

/*
	Zip++ application
	Copyright 2000, 2003 Zip-Technology.com

	@Author Ronan Merien <ronan.merien@zip-technology.com>
	@Version 1.2, 2003/10/15
*/

import java.applet.Applet;
import java.io.OutputStream;
import java.io.IOException;
import netscape.javascript.JSObject;
import java.util.Vector;

/**
	<p>A javascript ouput stream let an application write on a based applet HTML page.</p>
*/
public class JavaScriptOutputStream extends OutputStream {

	public static Applet applet;

	protected Vector vline;
	protected String line;

	public JavaScriptOutputStream() {
		vline = new Vector();
		line = new String();
	}

	public void write(int i) throws IOException
	{
		try
		{
			if (line.length() < 255) {
				line = line + (char) i;
			}
			else {
				// System.out.println(line);
				vline.addElement(line);
				line = new String();

				line = String.valueOf((char) i);
			}
		}
		catch (Exception e) {
		}
	}

	public void close() throws IOException
	{
		try
		{
			if (line.length() > 0) {
				vline.addElement(line);
				// System.out.println(line);
			}

			String args[] = new String[vline.size()];
			for (int i=0; i<vline.size(); i++) {
				args[i] = (String) vline.elementAt(i);
			}

			// String args[] = { "<HTML>","<BODY>", "Hello","</BODY>","</HTML>" };

			JSObject win = JSObject.getWindow(applet);
			JSObject doc = (JSObject) win.getMember("document");
			doc.call("write", args);

		}
		catch (Exception e) {
		}
	}
}