package ch.flowxperts.bonita;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.Serializable;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.bonitasoft.engine.api.ApiAccessType;
import org.bonitasoft.engine.api.LoginAPI;
import org.bonitasoft.engine.api.ProcessAPI;
import org.bonitasoft.engine.api.TenantAPIAccessor;
import org.bonitasoft.engine.bpm.data.DataNotFoundException;
import org.bonitasoft.engine.bpm.document.DocumentAttachmentException;
import org.bonitasoft.engine.bpm.document.DocumentValue;
import org.bonitasoft.engine.bpm.flownode.FlowNodeExecutionException;
import org.bonitasoft.engine.bpm.flownode.HumanTaskInstance;
import org.bonitasoft.engine.bpm.flownode.HumanTaskInstanceSearchDescriptor;
import org.bonitasoft.engine.bpm.process.ProcessActivationException;
import org.bonitasoft.engine.bpm.process.ProcessDefinitionNotFoundException;
import org.bonitasoft.engine.bpm.process.ProcessExecutionException;
import org.bonitasoft.engine.bpm.process.ProcessInstanceNotFoundException;
import org.bonitasoft.engine.exception.BonitaHomeNotSetException;
import org.bonitasoft.engine.exception.SearchException;
import org.bonitasoft.engine.exception.ServerAPIException;
import org.bonitasoft.engine.exception.UnknownAPITypeException;
import org.bonitasoft.engine.exception.UpdateException;
import org.bonitasoft.engine.expression.Expression;
import org.bonitasoft.engine.expression.ExpressionBuilder;
import org.bonitasoft.engine.expression.ExpressionType;
import org.bonitasoft.engine.expression.InvalidExpressionException;
import org.bonitasoft.engine.operation.Operation;
import org.bonitasoft.engine.operation.OperationBuilder;
import org.bonitasoft.engine.platform.LoginException;
import org.bonitasoft.engine.search.SearchOptionsBuilder;
import org.bonitasoft.engine.search.SearchResult;
import org.bonitasoft.engine.session.APISession;
import org.bonitasoft.engine.util.APITypeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.beust.jcommander.JCommander;

import ch.flowxperts.tools.*;

public class BonitaStarter {
	private static final String PROPERTIES_FILENAME = "BonitaStarter.properties";

	private static Document xmlDoc;
	private static ProcessAPI processAPI;
	private static boolean convertDocument;
	private static Properties prop;

	final static Logger logger = LoggerFactory.getLogger(BonitaStarter.class);

	public static void main(String[] args) {

		// assume SLF4J is bound to logback in the current environment
		// LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
		// print logback's internal status
		// StatusPrinter.print(lc);
		logger.info("*************************************************************************************************");
		logger.info("bonita-start started with arguments {}.", Arrays.toString(args));
		logger.info("*************************************************************************************************");

		// load properties
		prop = new Properties();
		try {
			File jarPath = new File(BonitaStarter.class.getProtectionDomain().getCodeSource().getLocation().getPath());
			String propertiesPath = URLDecoder.decode(jarPath.getParentFile().getAbsolutePath(), "UTF-8");
			logger.debug("application base path is {}", propertiesPath);
			prop.load(new FileInputStream(propertiesPath + "/conf/" + PROPERTIES_FILENAME));
		} catch (IOException e1) {
			logger.error("Error loading Properties.", e1);
			System.exit(-1);
		}
		if (prop.containsKey("convert") && prop.getProperty("convert").equalsIgnoreCase("FALSE"))
			convertDocument = false;
		else
			convertDocument = true;

		// read command line params
		CommandLineArgs cmdLineArgs = new CommandLineArgs();
		JCommander jComm = new JCommander(cmdLineArgs, args);
		String bonitaDocumentName = "incomingDocument";
		if (cmdLineArgs.docname != null) // document variable name supplied
			bonitaDocumentName = cmdLineArgs.docname;

		List<BonitaData> bonitaDataList = new ArrayList<BonitaData>();
		if (cmdLineArgs.stackId != null) // smartfix case
		{
			logger.info("Submitted StackID is {}.", cmdLineArgs.stackId);
			bonitaDataList = getBonitaDataFromStackId(cmdLineArgs.stackId);

		} else if (cmdLineArgs.filename != null) // single file case
		{
			logger.info("Submitted Filename is {}.", cmdLineArgs.filename);
			BonitaData data = new BonitaData();
			data.documents = new HashMap<String, Object>();
			data.variables = new HashMap<String, Object>();
			
			data.documents.put("invoiceReference", "http://www.flowxperts.ch");
			data.documents.put(bonitaDocumentName, loadFile(cmdLineArgs.filename));
			data.documentFilename = cmdLineArgs.filename;
			bonitaDataList.add(data);

		} else {
			jComm.usage();
			System.exit(0);
		}

		loginToBonita();
		createCasesWithDocument(bonitaDataList);
	}



	private static List<BonitaData> getBonitaDataFromStackId(String sFStackId) {

		List<BonitaData> bonitaDataList = new ArrayList<BonitaData>();
		// look for export....xml
		String sFStackPath = prop.getProperty("SmartFixExchangePath") + File.separator + sFStackId;
		File exportdir = new File(sFStackPath);
		if (!(exportdir.exists() && exportdir.isDirectory())) {
			logger.error("Directory {} does not exist! Terminating..", sFStackId);
			System.exit(-1);
		}

		// look for xmls
		File[] files = exportdir.listFiles(new FilenameFilter() {
			public boolean accept(File dir, String name) {
				return name.startsWith("Export") && name.endsWith(".xml");
			}
		});

		if (files != null) {
			logger.info("Stack {} consists of {} xml files..", sFStackId, files.length);
			for (File child : files) {
				String sfXMLFileName = child.getAbsolutePath();
				// read Export.xml
				try {
					DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
					DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
					xmlDoc = dBuilder.parse(new File(sfXMLFileName));
					// optional, but recommended
					// read this -
					// http://stackoverflow.com/questions/13786607/normalization-in-dom-parsing-with-java-how-does-it-work
					xmlDoc.getDocumentElement().normalize();
					logger.info("Start parsing xml file {}...", sfXMLFileName);

					// process each document
					NodeList nList = xmlDoc.getElementsByTagName("DOCUMENT");
					logger.info("Number of documents in this xml file is {}.", nList.getLength());
					
					for (int temp = 0; temp < nList.getLength(); temp++) {
						logger.info("Start processing Document {} of {}.", temp + 1, nList.getLength());
						Node nNode = nList.item(temp);
						if (nNode.getNodeType() == Node.ELEMENT_NODE) {
							Element documentElement = (Element) nNode;
							String documentFilename = getFilenameForSFDocument(documentElement, sFStackPath);
							BonitaData bd = new BonitaData();
							bd.documents = new HashMap<String, Object>();
							bd.variables = new HashMap<String, Object>();
							bd.variables.put("sfDocId",  documentElement.getAttribute("DocID"));
							bd.variables.put("sfStackId",  sFStackId);
							bd.variables.put(
									"sfXml",
									readFile(sfXMLFileName, StandardCharsets.UTF_8));
							bd.documents.put("invoiceReference", "http://www.flowxperts.ch");
							bd.documents.put("sfDocument", loadFile(documentFilename));														
							bd.documentFilename = documentFilename;
							bonitaDataList.add(bd);
						}
					}

				} catch (ParserConfigurationException e) {
					logger.error("Error reading export xml.", e);
					System.exit(-1);
				} catch (SAXException e) {
					logger.error("Error reading export xml.", e);
					System.exit(-1);
				} catch (IOException e) {
					logger.error("Error reading export xml.", e);
					System.exit(-1);
				} catch (RuntimeException e) {
					logger.error("RuntimeException", e);
					System.exit(-1);
					
				}
			}
		} else {
			logger.error("No xml files found..");
			System.exit(-1);
		}

		logger.info("Processing of Stack with ID {} finished.", sFStackId);
		return bonitaDataList;
	}

	private static String getFilenameForSFDocument(Element eElement, String documentBasePath) {
		NodeList pageNodes = eElement.getElementsByTagName("PAGE");
		String docId = eElement.getAttribute("DocID");
		int noOfPages = pageNodes.getLength();
		logger.info("Document Id is {}. Document has {} page(s)..", docId, noOfPages);

		String convertedFilePath = "";
		// convert tiff to pdf
		// check first if all pages are tiff
		boolean isTiff = true;
		String pagePath = "";
		for (int i = 0; i < noOfPages; i++) {
			pagePath = ((Element) pageNodes.item(i)).getAttribute("Path");
			String pageExtension = pagePath.substring(pagePath.lastIndexOf("."));
			isTiff = isTiff && (pageExtension.equalsIgnoreCase(".TIF") | pageExtension.equalsIgnoreCase(".TIFF"));
		}
		if (convertDocument && isTiff) {
			// use pagename as filename if only one page available
			convertedFilePath = documentBasePath + File.separator + docId + ".pdf";
			if (noOfPages == 1) {
				String fileName = pagePath.substring(pagePath.lastIndexOf(File.separator) + 1);
				convertedFilePath = documentBasePath + File.separator
						+ fileName.substring(0, fileName.lastIndexOf(".")) + ".pdf";
			}

			TiffToPdf ttp = new TiffToPdf(convertedFilePath);
			for (int i = 0; i < noOfPages; i++) {
				if (pageNodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
					Element currentElem = (Element) pageNodes.item(i);
					File origFile = new File(currentElem.getAttribute("Path"));
					if (!origFile.exists())
						logger.info("Document {} does not exist. skipping...", origFile.getAbsolutePath());
					ttp.addTiff(origFile.getAbsolutePath());
				}
			}
			ttp.close();
		}

		// multiple pages as separate files are only supported when converting
		// is enabled
		else {
			if (noOfPages != 1) {
				logger.error("multiple files per document are only supported if conversion is enabled");
				System.exit(-1);
			} else {
				convertedFilePath = ((Element) (pageNodes.item(0))).getAttribute("Path");
			}
		}
		return convertedFilePath;

		

	}
	private static void createCasesWithDocument(List<BonitaData> bonitaDataList) {
		
		int i = 1;
		for (BonitaData bonitaData : bonitaDataList) {
			logger.info("Bonita process for document {} successfully started. (filename = {})", i, bonitaData.documentFilename);
			boolean success = createCaseWithDocument(	bonitaData.variables, bonitaData.documents, bonitaData.documentFilename);
			if (success)
				logger.info("Bonita process for document {} successfully started. (filename = {})", i, bonitaData.documentFilename);
			else
				logger.error("Error while trying to start Bonita process for document {}. (filename = {})", i, bonitaData.documentFilename);
			i++;
		}
		
		
	}
	
	public static boolean createCaseWithDocument(
			Map<String, Object> variables, Map<String, Object> documents, String documentFilename) {
		
		// start bonita process for this document
		try {
			long processDefinitionId = processAPI.getProcessDefinitionId(prop.getProperty("BonitaServerProcessName"), prop.getProperty("BonitaServerProcessVersion"));
			// ----- create list of operations -----
			List<Operation> listOperations = new ArrayList<Operation>();
			// variables
			Map<String, Serializable> listExpressionsContext = new HashMap<String, Serializable>();
			if (variables != null)
				{
				for (String variableName : variables.keySet()) {
			
				if (variables.get(variableName) == null || (!(variables.get(variableName) instanceof Serializable)))
					continue;
				Object value = variables.get(variableName);
				Serializable valueSerializable = (Serializable) value;
				variableName = variableName.toLowerCase();
				Expression expr = new ExpressionBuilder().createExpression(variableName, variableName, value.getClass()
						.getName(), ExpressionType.TYPE_INPUT);
				Operation op = new OperationBuilder().createSetDataOperation(variableName, expr);
				listOperations.add(op);
				listExpressionsContext.put(variableName, valueSerializable);
				}
			}
			// update document
			for (String documentName : documents.keySet()) {
				if (documents.get(documentName) == null)
					continue;
				DocumentValue documentValue = null;
				if (documents.get(documentName) instanceof String) {
					documentValue = new DocumentValue(((String) documents.get(documentName)));
				} else if (documents.get(documentName) instanceof byte[]) {
					// byte
					documentValue = new DocumentValue(((byte[]) documents.get(documentName)), "plain/text",
							documentFilename);
				} else if (documents.get(documentName) instanceof ByteArrayOutputStream) {
					documentValue = new DocumentValue(
							((ByteArrayOutputStream) documents.get(documentName)).toByteArray(), "plain/text",
							documentFilename);
					// url
				}
				Operation docRefOperation = new OperationBuilder().createSetDocument(
						documentName,
						new ExpressionBuilder().createInputExpression(documentName + "Reference",
								DocumentValue.class.getName()));
				listOperations.add(docRefOperation);
				listExpressionsContext.put(documentName + "Reference", documentValue);
			}
			// ----- start process instance -----
			processAPI.startProcess(processDefinitionId, listOperations, listExpressionsContext);

		} catch (ProcessDefinitionNotFoundException e) {
			logger.error("Unable to start Process {} Version{}. Process Definition not found.",
					prop.getProperty("BonitaServerProcessName"), prop.getProperty("BonitaServerProcessVersion"), e);
			return false;
		} catch (ProcessActivationException e) {
			logger.error("Unable to start Process {} Version{}. Process cannot be activated",
					prop.getProperty("BonitaServerProcessName"), prop.getProperty("BonitaServerProcessVersion"), e);
			return false;
		} catch (ProcessExecutionException e) {
			logger.error("Unable to start Process {} Version{}. Process cannot be executed.",
					prop.getProperty("BonitaServerProcessName"), prop.getProperty("BonitaServerProcessVersion"), e);
			return false;
		} catch (InvalidExpressionException e) {
			logger.error("Unable to start Process {} Version{}", prop.getProperty("BonitaServerProcessName"),
					prop.getProperty("BonitaServerProcessVersion"), e);
			return false;
		}
		return true;
	}
	
	private static void loginToBonita() {
		// login to bonita
		try {
			logger.info(
					"Login to Bonita BPM \n BonitaServerBaseURL: {} \n BonitaServerApplicationName: {} \n BonitaServerUser: {}",
					prop.getProperty("BonitaServerBaseURL"), prop.getProperty("BonitaServerApplicationName"),
					prop.getProperty("BonitaServerUser"));
			Map<String, String> map = new HashMap<String, String>();
			map.put("server.url", prop.getProperty("BonitaServerBaseURL"));
			map.put("application.name", prop.getProperty("BonitaServerApplicationName"));
			APITypeManager.setAPITypeAndParams(ApiAccessType.HTTP, map); // get
																			// the
																			// LoginAPI
																			// using
																			// the
																			// TenantAPIAccessor
			LoginAPI loginAPI = TenantAPIAccessor.getLoginAPI(); // Set the
																	// username
																	// and
																	// password
																	// for the
																	// application
			APISession apiSession = loginAPI.login(prop.getProperty("BonitaServerUser"),
					prop.getProperty("BonitaServerPW"));
			processAPI = TenantAPIAccessor.getProcessAPI(apiSession);

			if (processAPI != null)
				logger.info("login to bonita successful.");
			else {
				logger.info("login to bonita not successful.");
				System.exit(-1);
			}
		} catch (BonitaHomeNotSetException e) {
			logger.error("Login to Bonita not successful: BonitaHome not set.", e);
			System.exit(-1);
		} catch (ServerAPIException e) {
			logger.error("Login to Bonita not successful: API Exception.", e);
			System.exit(-1);
		} catch (UnknownAPITypeException e) {
			logger.error("Login to Bonita not successful: API Exception.", e);
			System.exit(-1);
		} catch (LoginException e) {
			logger.error("Login to Bonita not successful: Login not valid.", e);
			System.exit(-1);
		}
	}

	/**
	 * * load the file give in parameters and return a byteArray or a null value
	 * * * @param fileName * @return
	 * */

	public static ByteArrayOutputStream loadFile(String fileName) {
		FileInputStream file = null;
		int read;
		try {
			file = new FileInputStream(fileName);
			ByteArrayOutputStream byteArray = new ByteArrayOutputStream();
			byte[] content = new byte[2048 * 16 * 16];
			while ((read = file.read(content)) > 0) {
				byteArray.write(content, 0, read);
			}
			return byteArray;
		} catch (FileNotFoundException e) {
			logger.error("Error reading file {}.", fileName, e);
		} catch (IOException e) {
			logger.error("Error reading file {}.", fileName, e);
		} finally {
			if (file != null)
				try {
					file.close();
				} catch (IOException e) {

				}
		}
		return null;
	}
	
	static String readFile(String path, Charset encoding)

	{
		String res = "";
		try {
			byte[] encoded = Files.readAllBytes(Paths.get(path));
			res = new String(encoded, encoding);
		} catch (IOException e) {
			logger.error("Error reading file {}.", path, e);
		}
		return res;
	}

}
