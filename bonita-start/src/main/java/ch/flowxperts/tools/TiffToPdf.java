package ch.flowxperts.tools;

import com.itextpdf.text.io.RandomAccessSourceFactory;
import com.itextpdf.text.pdf.RandomAccessFileOrArray;
//Read Tiff File, Get number of Pages
import com.itextpdf.text.pdf.codec.TiffImage;

//We need the library below to write the final 
//PDF file which has our image converted to PDF
import java.io.FileOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//The image class to extract separate images from Tiff image
import com.itextpdf.text.Image;
//PdfWriter object to write the PDF document
import com.itextpdf.text.pdf.PdfWriter;
//Document object to add logical image files to PDF
import com.itextpdf.text.Document;

public class TiffToPdf {

	final static Logger logger = LoggerFactory.getLogger(TiffToPdf.class);
	private Document tifftoPDF;

	public TiffToPdf(String pdfFilename) {
		try {

			tifftoPDF = new Document();
			tifftoPDF.setMargins(0, 0, 0, 0);

			PdfWriter.getInstance(tifftoPDF, new FileOutputStream(pdfFilename));
			tifftoPDF.open();
			tifftoPDF.addAuthor("flowXperts.ch");

		} catch (Exception i1) {
			i1.printStackTrace();
		}

	}

	public void addTiff(String tifFilename) {
		try {
			// Read the Tiff File
			RandomAccessSourceFactory rasf = new RandomAccessSourceFactory();
			RandomAccessFileOrArray myTiffFile = new RandomAccessFileOrArray(rasf.createBestSource(tifFilename));
			// Find number of images in Tiff file
			int numberOfPages = TiffImage.getNumberOfPages(myTiffFile);
			logger.info("Number of Images in Tiff File: {}", numberOfPages);

			// Run a for loop to extract images from Tiff file
			// into a Image object and add to PDF recursively
			for (int i = 1; i <= numberOfPages; i++) {
				Image tempImage = TiffImage.getTiffImage(myTiffFile, i);
				logger.debug("tiff dimension :");
				logger.debug("pixels: {} x {}",tempImage.getWidth(), tempImage.getHeight());
				logger.debug("inch: {} x {}", tempImage.getWidth() / tempImage.getDpiX(), tempImage.getHeight() / tempImage.getDpiY());
				logger.debug("cm: {} x {}", tempImage.getWidth() * 2.54 / tempImage.getDpiX(), tempImage.getHeight() * 2.54 / tempImage.getDpiY());
				logger.debug("dpi: {} x {}", tempImage.getDpiX(), tempImage.getDpiY());
				logger.debug("size of pdf: {} x {}", tifftoPDF.getPageSize().getWidth(), tifftoPDF.getPageSize().getHeight());
				float newx = (float) tempImage.getWidth() * ((float) 72 / tempImage.getDpiX());
				float newy = (float) tempImage.getHeight() * ((float) 72 / tempImage.getDpiY());
				logger.debug("new calculated size: {} x {}", newx, newy);
				tempImage.scaleAbsolute(newx, newy);

				tifftoPDF.add(tempImage);

			}

		} catch (Exception i1) {
			i1.printStackTrace();
		}

	}

	public void close() {
		tifftoPDF.close();
		logger.info("Tiff to PDF Conversion in Java Completed");
	}
}
