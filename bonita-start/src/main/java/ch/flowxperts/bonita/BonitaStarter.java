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

import ch.flowxperts.tools.*;


public class BonitaStarter {
	private static final String PROPERTIES_FILENAME = "BonitaStarter.properties";

	private static String sFStackId;
	private static Document xmlDoc;
	private static ProcessAPI processAPI;
	private static boolean convertDocument;
	private static Properties prop;

	
	final static Logger logger = LoggerFactory.getLogger(BonitaStarter.class);

	public static void main(String[] args) {

		// assume SLF4J is bound to logback in the current environment
	    //LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
	    // print logback's internal status
	    // StatusPrinter.print(lc);
		logger.info("*************************************************************************************************");
		logger.info("bonita-start started with arguments {}." , Arrays.toString(args));
		logger.info("*************************************************************************************************");
		
		prop = new Properties();
		try {
		        File jarPath=new File(BonitaStarter.class.getProtectionDomain().getCodeSource().getLocation().getPath());
		        String propertiesPath= URLDecoder.decode(jarPath.getParentFile().getAbsolutePath(),"UTF-8");
		        logger.debug("application base path is {}", propertiesPath);
		        prop.load(new FileInputStream(propertiesPath+"/conf/" + PROPERTIES_FILENAME));
		    } catch (IOException e1) {
		    	logger.error("Error loading Properties.", e1);
				System.exit(-1);
		    }
		
		
		if (prop.containsKey("convert") && prop.getProperty("convert").equalsIgnoreCase("FALSE"))
			convertDocument = false;
		else
			convertDocument = true;

		sFStackId = "";

		// read command line params
		if (args.length > 0)
			sFStackId = args[0];
		else {
			logger.error("No StackId specified! Terminating..");
			System.exit(-1);
		}

		logger.info("Submitted StackID is {}.", sFStackId);

		// check if file [stackid].sf_export_finished exists and look for
		// export....xml  
		File exportdir = new File(prop.getProperty("SmartFixExchangePath") + File.separator + sFStackId);
		if (!(exportdir.exists() && exportdir.isDirectory())) {
			logger.error("Directory {} does not exist! Terminating..", sFStackId);
			System.exit(-1);
		}
		File successFile = new File(prop.getProperty("SmartFixExchangePath") + File.separator + sFStackId
				+ ".sf_export_finished");
		if (!successFile.exists()) {
			logger.error("File {}.sf_export_finished does not exist! Terminating..", sFStackId);
			System.exit(-1);
		}
	

		
		
		
		// look for xmls
		File[] files = exportdir.listFiles(new FilenameFilter() {
				public boolean accept(File dir, String name) {
					return name.startsWith("Export") && name.endsWith(".xml");
				}
			});

			if (files != null) {
				logger.info("Found {} xml documents..", files.length);
				for (File child : files) {
					processSmartFixExportXML(child.getAbsolutePath());
				}
			} else {
				logger.error("No xml files found..");
				System.exit(-1);
			}
		
		logger.info("Processing of Stack with ID {} finished.", sFStackId);
		System.exit(0);
	}

	private static void processSmartFixExportXML(String exportxmlFilename) {
		// read Export.xml
		try {
			File smartFixExportXmlFile = new File(exportxmlFilename);
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			xmlDoc = dBuilder.parse(smartFixExportXmlFile);
			// optional, but recommended
			// read this -
			// http://stackoverflow.com/questions/13786607/normalization-in-dom-parsing-with-java-how-does-it-work
			xmlDoc.getDocumentElement().normalize();
			logger.info("XMLFile {} loaded.", exportxmlFilename);

			// login to bonita
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
			else
			{
				logger.info("login to bonita not successful.");
				System.exit(-1);
			}
				

			// process each document
			NodeList nList = xmlDoc.getElementsByTagName("DOCUMENT");

			logger.info("Number of documents in this stack is {}.", nList.getLength());

			for (int temp = 0; temp < nList.getLength(); temp++) {

				logger.info("Start processing Document {} of {}.", temp + 1, nList.getLength());

				Node nNode = nList.item(temp);
				if (nNode.getNodeType() == Node.ELEMENT_NODE) {

					processDocument((Element) nNode, smartFixExportXmlFile.getName());

				}
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
		
	} catch (ParserConfigurationException e) {
		logger.error("Error reading export xml.", e);
		System.exit(-1);
	} catch (SAXException e) {
		logger.error("Error reading export xml.", e);
		System.exit(-1);
	} catch (IOException e) {
		logger.error("Error reading export xml.", e);
		System.exit(-1);
	}
		catch (RuntimeException e)
		{
			logger.error("RuntimeException", e);
			System.exit(-1);			
		}

		// TODO: delete [stackid].sf_export_finished and corresponding
		// folder
	}

	private static void processDocument(Element eElement, String xmlFileName) {
		String docId = eElement.getAttribute("DocID");
		NodeList pageNodes = eElement.getElementsByTagName("PAGE");
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
			//use pagename as filename if only one page available
			convertedFilePath = prop.getProperty("SmartFixExchangePath") + "/" + sFStackId + "/" + docId + ".pdf";
			if (noOfPages == 1){
				String fileName = pagePath.substring(pagePath.lastIndexOf(File.separator)+1); 
				convertedFilePath = prop.getProperty("SmartFixExchangePath") + "/" + sFStackId + "/" +  fileName.substring(0,fileName.lastIndexOf(".")) +
						".pdf";
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

		// multiple pages as separate files are only supported when converting is enabled
		else {
			if (noOfPages != 1) {
				logger.error("multiple files per document are only supported if conversion is enabled");
				System.exit(-1);
			} else {
				convertedFilePath = ((Element) (pageNodes.item(0))).getAttribute("Path");
			}
		}
		String documentFilename = new File(convertedFilePath).getName();

		// start bonita process for this document
		logger.info("Start Bonita process for document {}, DocId: {}", documentFilename, docId);
		Map<String, Object> firstInvoice = new HashMap<String, Object>();
		firstInvoice.put("sfDocId", docId);
		firstInvoice.put(
				"sfXml",
				readFile(prop.getProperty("SmartFixExchangePath") + File.separator + sFStackId + File.separator
						+ xmlFileName, StandardCharsets.UTF_8));

		Map<String, Object> firstInvoiceDocument = new HashMap<String, Object>();
		firstInvoiceDocument.put("invoiceReference", "http://www.flowxperts.ch");
		firstInvoiceDocument.put("sfDocument", loadFile(convertedFilePath));

		try {
			createCaseWithDocument(prop.getProperty("BonitaServerProcessName"),
					prop.getProperty("BonitaServerProcessVersion"), firstInvoice, firstInvoiceDocument,
					documentFilename, processAPI);
		} catch (ProcessDefinitionNotFoundException e) {
			logger.error("Unable to start Process {} Version{}. Process Definition not found.",
					prop.getProperty("BonitaServerProcessName"),
					prop.getProperty("BonitaServerProcessVersion"), e);
			System.exit(-1);
		} catch (ProcessActivationException e) {
			logger.error("Unable to start Process {} Version{}. Process cannot be activated",
					prop.getProperty("BonitaServerProcessName"),
					prop.getProperty("BonitaServerProcessVersion"), e);
			System.exit(-1);
		} catch (ProcessExecutionException e) {
			logger.error("Unable to start Process {} Version{}. Process cannot be executed.",
					prop.getProperty("BonitaServerProcessName"),
					prop.getProperty("BonitaServerProcessVersion"), e);
			System.exit(-1);
		} catch (InvalidExpressionException e) {
			logger.error("Unable to start Process {} Version{}", prop.getProperty("BonitaServerProcessName"),
					prop.getProperty("BonitaServerProcessVersion"), e);
			System.exit(-1);
		}

		logger.info("Bonita process for document {}, DocId: {} successfully started.", documentFilename, docId);

	}

	/**
	 * * @param processDefinitionName * @param processVersion * @param variables
	 * * @param processAPI * @throws DataNotFoundException * @throws
	 * ProcessDefinitionNotFoundException * @throws UpdateException * @throws
	 * SearchException * @throws DocumentAttachmentException * @throws
	 * ProcessInstanceNotFoundException * @throws FlowNodeExecutionException
	 * */
	public static void processCase(String processDefinitionName, String processVersion, String activityName,
			long invoiceID, Map<String, Object> variablesToUpdate, Map<String, Object> documentsToUpdate, long userId,
			ProcessAPI processAPI) throws DataNotFoundException, ProcessDefinitionNotFoundException, UpdateException,
			SearchException, ProcessInstanceNotFoundException, DocumentAttachmentException, FlowNodeExecutionException {

		long processDefinitionId = processAPI.getProcessDefinitionId(processDefinitionName, processVersion);
		SearchOptionsBuilder searchOptionBuilder = new SearchOptionsBuilder(0, 100);
		searchOptionBuilder.filter(HumanTaskInstanceSearchDescriptor.NAME, activityName);
		searchOptionBuilder.filter(HumanTaskInstanceSearchDescriptor.PROCESS_DEFINITION_ID, processDefinitionId);
		// In the Subcription version, it is possible, using the Key Search, to
		// limit the scope by the invoiceId value.
		// In the Community version, all cases must be retrieved and filter has
		// to be
		// done afterwards, as below.
		SearchResult<HumanTaskInstance> searchHumanTaskInstances = processAPI.searchPendingTasksForUser(userId,
				searchOptionBuilder.done());
		for (HumanTaskInstance pendingTask : searchHumanTaskInstances.getResult()) {
			Long pendingTaskInvoiceId = (Long) processAPI.getActivityDataInstance("invoiceId", pendingTask.getId())
					.getValue();
			if (pendingTaskInvoiceId == null || pendingTaskInvoiceId.longValue() != invoiceID) {
				continue;
			}
			// we get the correct one !
			processAPI.assignUserTask(pendingTask.getId(), userId);
			// update variable
			for (String variableName : variablesToUpdate.keySet()) {
				processAPI.updateActivityDataInstance(variableName, pendingTask.getId(),
						(Serializable) variablesToUpdate.get(variableName));
			}
			// update the document
			for (String documentName : documentsToUpdate.keySet()) {
				if (documentsToUpdate.get(documentName) instanceof String)
					processAPI.attachDocument(pendingTask.getId(), documentName, "TheFileName", "application/pdf",
							(String) documentsToUpdate.get(documentName));
				else if (documentsToUpdate.get(documentName) instanceof ByteArrayOutputStream)
					processAPI.attachDocument(pendingTask.getId(), documentName, "TheFileName", "application/pdf",
							((ByteArrayOutputStream) documentsToUpdate.get(documentName)).toByteArray());
			}
			// execute the task
			processAPI.executeFlowNode(pendingTask.getId());
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

	public static void createCaseWithDocument(String processDefinitionName, String processVersion,
			Map<String, Object> variables, Map<String, Object> documents, String documentFilename, ProcessAPI processAPI)
			throws ProcessDefinitionNotFoundException, InvalidExpressionException, ProcessActivationException,
			ProcessExecutionException {
		long processDefinitionId = processAPI.getProcessDefinitionId(processDefinitionName, processVersion);
		// ----- create list of operations -----
		List<Operation> listOperations = new ArrayList<Operation>();
		// variables
		Map<String, Serializable> ListExpressionsContext = new HashMap<String, Serializable>();
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
			ListExpressionsContext.put(variableName, valueSerializable);
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
				documentValue = new DocumentValue(((ByteArrayOutputStream) documents.get(documentName)).toByteArray(),
						"plain/text", documentFilename);
				// url
			}
			Operation docRefOperation = new OperationBuilder().createSetDocument(
					documentName,
					new ExpressionBuilder().createInputExpression(documentName + "Reference",
							DocumentValue.class.getName()));
			listOperations.add(docRefOperation);
			ListExpressionsContext.put(documentName + "Reference", documentValue);
		}
		// ----- start process instance -----
		processAPI.startProcess(processDefinitionId, listOperations, ListExpressionsContext);
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
