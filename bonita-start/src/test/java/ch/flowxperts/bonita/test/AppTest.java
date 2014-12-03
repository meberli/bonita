package ch.flowxperts.bonita.test;

import java.io.File;
import java.net.URL;

import ch.flowxperts.tools.TiffToPdf;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Unit test for simple App.
 */
public class AppTest 
    extends TestCase
{
    /**
     * Create the test case
     *
     * @param testName name of the test case
     */
    public AppTest( String testName )
    {
        super( testName );
    }

    /**
     * @return the suite of tests being tested
     */
    public static Test suite()
    {
        return new TestSuite( AppTest.class );
    }

    
    public void testPDF()
    {
		//tiff to pfdf conversion testhh
    	
		File f = new File("out.pdf");
		if (f.exists())
			f.delete();

		
		TiffToPdf ttp = new TiffToPdf("out.pdf");
		
		URL url = Thread.currentThread().getContextClassLoader().getResource("testdata");
		File dir = new File(url.getPath());
		//File dir = new File("testdata");
		  File[] directoryListing = dir.listFiles();
		  if (directoryListing != null) {
		    for (File child : directoryListing) {
		      ttp.addTiff(child.getAbsolutePath());
		    }
		  }
		  ttp.close();
		  
		  boolean res = f.exists() && !f.isDirectory();
		  assertEquals("pdf file is not as expected",  true, res);    	
    }
    
}
