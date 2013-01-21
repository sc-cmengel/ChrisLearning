package com.inquira.custom.cca;

import gnu.trove.TIntArrayList;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
//import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;

import com.inquira.util.IQEnum;
import com.inquira.content.ContentSourceType;
import com.inquira.content.ContentStoreHelper;
import com.inquira.infra.Execution;
import com.inquira.request.ErrorResponse;
import com.inquira.request.HandlerException;
import com.inquira.request.ILogConstants;
import com.inquira.request.INameConstants;
import com.inquira.request.NamedHandler;
import com.inquira.request.RequestContext;
import com.inquira.request.cca.CCAAnswerContext;
import com.inquira.request.cca.CCAHandler;
import com.inquira.request.cca.CCASolutionHandler;
import com.inquira.retrieve.newIndex.CollectionDataStore;
import com.inquira.util.xml.Node;
import com.inquira.util.xml.NodeBuilder;
import com.inquira.custom.cca.helpers.Linkage;
import com.inquira.custom.cca.CRMInteraction;

/**
 * Single class to handle both CCA Requests and CCA Responses
 * Requests handle Add and Remove activity
 * Responses return a list of linked articles for a Case
 */
public class CCARemedyHandler implements CCASolutionHandler, NamedHandler, ILogConstants {

	private static final boolean HANDLER_DEBUG = true;
	
    private static final String DEBUG_MESSAGE = "CCA_REMEDY_HANDLER_DEBUG_MESSAGE";
    private static final String ERROR_MESSAGE = "CCA_REMEDY_HANDLER_ERROR_MESSAGE";
    private static final String errorPrefix = "CCA_REMEDY_HANDLER_ERROR_MESSAGE:";

	public static final String CCA_CHECKED_ANSWERS_LIST = "CCACheckedAnswersList";
    
    private static final String USERID_PARAM = INameConstants.USER_AGENT_PARAMETER_PREFIX + "userid";
    private static final String FAILED = "[failed]";
    private static final String LINK = "link";
    private static final String LINK_UNLINK_RESULT = "linkUnlinkResult";
	private static final String SUCCESS = "[success]";
	private static final String UNLINK = "unlink";
	private static final String CCA_INTERNAL_ERROR = "RemedyCCAInternalError"; 
	
    private static final String ACCESS_TOKEN = "access_token";
    
    private static String CRM_URL = ""; 
    
    private String crmAccessToken = null;
    private String srKey = null;

	private StringBuffer imdocids = new StringBuffer("");
	
	private HashMap<Integer,String> mapIMDocID;
	private HashMap<Integer,String> mapIMGUID;
	private HashMap<String,String> mapIMVersion;
    
	private void debugMsg(String strDebug) {
		if (HANDLER_DEBUG) {	
			System.out.println("HANDLER_DEBUG: " + strDebug); 
		}
	}
	
	private void errorMsg(String strError) {
		System.out.println(errorPrefix + strError);
	}
	
	/**
	 * Initialize the CCA Hander - Specifically set the CRM URL
	 */
	public void init(String url, String username, String password, Properties properties) throws HandlerException {

		CRM_URL = url;
		
		Execution.context().log().event(DEBUG_MSG, DEBUG_MESSAGE, "init called: " + url + ":" + username + ":" +  password + ":" + properties.toString());
		
	}
	
	/**
	 * Primary method to process all CCA Requests
	 */
	public RequestContext handle(RequestContext rc) throws HandlerException {
		
		Execution.context().log().event(DEBUG_MSG, DEBUG_MESSAGE, "handle: " + rc.getType() + ":" + rc.get("ui_mode"));
		
		// Prevent duplicate calls to the handle method
		String ccaProcessed = (String)rc.getParam(LINK_UNLINK_RESULT);
		if (!StringUtils.isEmpty(ccaProcessed)) {
			Execution.context().log().event(DEBUG_MSG, DEBUG_MESSAGE, "Already processed " + rc.toString() + " so just returning...");
			debugMsg("Already processed " + rc.toString() + " so just returning...");
			return rc;
		}
		
		crmAccessToken = rc.getParam(ACCESS_TOKEN);
		
		if(crmAccessToken == null || crmAccessToken.equals("null") || crmAccessToken.length() == 0){
			errorMsg( "No Salesforce access_token specified");
			Execution.context().log().event(ERROR_MSG, ERROR_MESSAGE, "No Salesforce access_token specified");
			throw new HandlerException(CCA_INTERNAL_ERROR, new Object[]{new ErrorResponse(CCA_INTERNAL_ERROR, "No salesforce token specified via access_token.")});
		}
		
		Execution.context().log().event(DEBUG_MSG, DEBUG_MESSAGE, "Salesforce access_token specified:" + crmAccessToken);
		
		srKey = (String) rc.get(INameConstants.CCA_SR_KEY);		
		
		if (!isValidString(srKey)){
			errorMsg("Error - srKey (Salesforce Case Number) is null");
			rc.setParam(LINK_UNLINK_RESULT, FAILED + "Error happened when adding case link due to null srKey (CRM service request number).");
			Execution.context().log().event(ERROR_MSG, ERROR_MESSAGE, "Error happened when adding case link due to null srKey (CRM service request number).");
			throw new HandlerException(CCA_INTERNAL_ERROR, new Object[]{new ErrorResponse(CCA_INTERNAL_ERROR, "Error happened when adding case link due to null srKey (CRM service request number)")});
		}
		
		if (rc.getType().equals("CCAAddSolution")){
			if (rc.get("ui_mode").equals("remove_solution")){
				rc = handleRemoveSolutionExternal(rc);
				try {
					Node linkedAnswers = buildResponseNode(rc);
					if (linkedAnswers != null){
						rc.setResponseNode(linkedAnswers);
					}
				} catch (Exception e) {
					Execution.context().log().event(ERROR_MSG, "ERROR_MESSAGE", (new StringBuilder()).append(errorPrefix).append("\n").append(e).toString());
				}
			}
			else {
				rc = handleAddSolution(rc);
				try {
					Node linkedAnswers = buildResponseNode(rc);
					if (linkedAnswers != null){
						rc.setResponseNode(linkedAnswers);
					}
				} catch (Exception e) {
					Execution.context().log().event(ERROR_MSG, "ERROR_MESSAGE", (new StringBuilder()).append(errorPrefix).append("\n").append(e).toString());
				}
			}
		} else if (rc.getType().equals("CCARemoveSolution")){
			return handleRemoveSolutionExternal(rc);
		} else {
			Execution.context().log().event(DEBUG_MSG, DEBUG_MESSAGE, "handle: Response");
			try {
				Node linkedAnswers = buildResponseNode(rc);
				if (linkedAnswers != null){
					rc.setResponseNode(linkedAnswers);
				}
			} catch (Exception e) {
				Execution.context().log().event(ERROR_MSG, "ERROR_MESSAGE", (new StringBuilder()).append(errorPrefix).append("\n").append(e).toString());
			}
		}
		
		return rc;
		
	}

	/**
	 * Process a CCA request to link an InQuira Search Result Item to a Case
	 */
	public RequestContext handleAddSolution(RequestContext rc) throws HandlerException {

		Execution.context().log().event(DEBUG_MSG, DEBUG_MESSAGE, "handleAddSolution");
		
		boolean linkageFailed = false;
		
		ArrayList<Linkage> linkageList = generateLinkageListForRequestContextAdd(rc);
		
		if(linkageList.size() > 0){

			CRMInteraction crmInteraction = new CRMInteraction(crmAccessToken, CRM_URL);

			for (Linkage linkage : linkageList) {
				Execution.context().log().event(DEBUG_MSG, DEBUG_MESSAGE, "Adding CRM linkage for: " + linkage.getDocumentId());
				if (!crmInteraction.add(srKey, linkage)) {
					linkageFailed = true;
				}
			}
		}

		rc.setParam(LINK_UNLINK_RESULT, SUCCESS);

		if(linkageFailed) {
			Execution.context().log().event(ERROR_MSG, DEBUG_MESSAGE, "Error communicating with CRM to add links.");
			rc.setParam(LINK_UNLINK_RESULT, FAILED + "There were errors connecting to the CRM server.");
			//doRollback(rc, UNLINK);

			throw new HandlerException(CCA_INTERNAL_ERROR, new Object[]{new ErrorResponse(CCA_INTERNAL_ERROR, "Error occured when add case link to CRM")});
		}		

		return rc;
	}

	/**
	 * Implemented to support CCASolutionHandler - Unused
	 */
	public void handleRemoveSolution(RequestContext rc) throws HandlerException {
	
		Execution.context().log().event(ERROR_MSG, ERROR_MESSAGE, "handleRemoveSolution called.  This method should not be used.");
		
	}
	
	/**
	 * Process a CCA request to remove a InQuira Search Result Item link to a Case
	 */
	public RequestContext handleRemoveSolutionExternal(RequestContext rc) throws HandlerException {
		
		Execution.context().log().event(DEBUG_MSG, DEBUG_MESSAGE, "handleRemoveSolutionExternal");
		
		boolean linkageFailed = false;
		
		ArrayList<Linkage> linkageList = generateLinkageListForRequestContextRemove(rc);
		
		if(linkageList.size() > 0){

			CRMInteraction crmInteraction = new CRMInteraction(crmAccessToken, CRM_URL);

			for (Linkage linkage : linkageList) {
				debugMsg("Removing CRM linkage for: " + linkage.getDocumentChecksum());
				if (!crmInteraction.remove(srKey, linkage)) {
					linkageFailed = true;
				}
			}
		}

		rc.setParam(LINK_UNLINK_RESULT, SUCCESS);

		if(linkageFailed) {
			Execution.context().log().event(ERROR_MSG, DEBUG_MESSAGE, "Error communicating with CRM to remove links.");
			errorMsg("Error communicating with CRM to remove links.");
			
			rc.setParam(LINK_UNLINK_RESULT, FAILED + "There were errors connecting to the CRM server.");
			//doRollback(rc, UNLINK);

			throw new HandlerException(CCA_INTERNAL_ERROR, new Object[]{new ErrorResponse(CCA_INTERNAL_ERROR, "Error occured when remove case link to CRM")});
		}		

		return rc;
		
	}


	public String getHandlerName() {
		return "Remedy Request Handler";
	}
	
	//Generate linked answers response
	public Node buildResponseNode(RequestContext rc) {
		String srKey = (String) rc.get("CCASRKey");
		String linkedAnswersResponseXML = null;
		if (srKey == null || srKey.equals("null")){
			Execution.context().log().event(ERROR_MSG, "ERROR_MESSAGE", "Error - srKey (Salesforce Case Number) is null");
		} else {
			linkedAnswersResponseXML = getAnswers(srKey);
		}
		
		return buildNode(linkedAnswersResponseXML);
	}

	public Node buildNode(String rawXML) {
		try {
			NodeBuilder nb = new NodeBuilder();
			Node node = (Node) nb.process(new StringReader(rawXML));
			return node;
		} catch (Exception e) {
			Execution.context().log().event(ERROR_MSG, "ERROR_MESSAGE", (new StringBuilder()).append(errorPrefix).append(" XML node build error for the rawXML.  \n").append(e).toString());
		}
		return null;
	}
	
	/**
	 * This method returns if the string has been specified.
	 * @param strValue String containing the value to check
	 */
	private boolean isValidString(String strValue) {
		return StringUtils.trimToNull(strValue) != null && !"null".equalsIgnoreCase(strValue);
	}
	
	
	/**
	 * This method retrieves the answers that are currently associated with the specified case.
	 */
	private String getAnswers(final String strSrKey) {

		// debugMsg("getAnswers(" + strSrKey + ")");
		
		final StringBuilder strBuffer = new StringBuilder();

		strBuffer.append("<ListOfInquiraSrLinkedAnswersIo>");
		
		CRMInteraction crmInteraction = new CRMInteraction(crmAccessToken, CRM_URL);
		
		final Iterator<Linkage> iterAnswers = crmInteraction.get(strSrKey); 
		
 		if (iterAnswers != null && iterAnswers.hasNext()) {
			strBuffer.append("  <ServiceRequest>");
			strBuffer.append("    <SRNumber>").append(strSrKey).append("</SRNumber>");
			strBuffer.append("    <ListOfInquiraAnswersEai>");

			while (iterAnswers.hasNext()) {
				final Linkage linkage = iterAnswers.next();
				// debugMsg("Got a linkage...");
				strBuffer.append("    <InquiraAnswersEai>");
				strBuffer.append("      <CaseLinkKey>").append(StringEscapeUtils.escapeXml(linkage.getDocumentGuid())).append("</CaseLinkKey>");
				// debugMsg("Got the GUID...");
				// strBuffer.append("      <Comments />");
				strBuffer.append("      <DocumentId>").append(StringEscapeUtils.escapeXml(linkage.getDocumentId())).append("</DocumentId>");
				// debugMsg("Got the DocID...");
				strBuffer.append("      <Excerpt>").append(StringEscapeUtils.escapeXml(linkage.getExcerpt())).append("</Excerpt>");
				// debugMsg("Got the GUID...");
				strBuffer.append("      <Key>").append(StringEscapeUtils.escapeXml(linkage.getDocumentChecksum())).append("</Key>");
				// strBuffer.append("      <LinkStatus>Link</LinkStatus>");
				// debugMsg("Got the checksum...");
				strBuffer.append("      <LinkedDate>").append(StringEscapeUtils.escapeXml(linkage.getReadableCreateDate())).append("</LinkedDate>");
				// debugMsg("Got the readable creation date...");
				// strBuffer.append("      <Status2>Some Status</Status2>");
				strBuffer.append("      <UserName>").append(StringEscapeUtils.escapeXml(linkage.getCreatorUser())).append("</UserName>");
				// debugMsg("Got the user name...");
				strBuffer.append("      <Title>").append(StringEscapeUtils.escapeXml(linkage.getTitle())).append("</Title>");
				// debugMsg("Got the title...");
				strBuffer.append("      <URL>").append(StringEscapeUtils.escapeXml(linkage.getUrl())).append("</URL>");
				// debugMsg("Got the URL...");
				strBuffer.append("      <VersionNumber>").append(StringEscapeUtils.escapeXml(linkage.getVersion())).append("</VersionNumber>");
				// debugMsg("Got the version number...");
				strBuffer.append("    </InquiraAnswersEai>");
			}

			strBuffer.append("    </ListOfInquiraAnswersEai>");
			strBuffer.append("  </ServiceRequest>");
		}
		strBuffer.append("</ListOfInquiraSrLinkedAnswersIo>");

		Execution.context().log().event(DEBUG_MSG, DEBUG_MESSAGE, "Answer Response:" + strBuffer.toString());

		return strBuffer.toString();
	}	
	
	
	private ArrayList<Linkage> generateLinkageListForRequestContextRemove(RequestContext rc) throws HandlerException{
		List<Linkage> listArticles = new ArrayList<Linkage>();
		
		String checkedAnswers = (String)rc.get(CCA_CHECKED_ANSWERS_LIST);
		StringTokenizer st = new StringTokenizer(checkedAnswers, ";");
		while(st.hasMoreElements()) {
			String checkSum = (String)st.nextElement();
			Linkage linkage = new Linkage();
			
			linkage.setDocumentChecksum(checkSum);
			debugMsg("Adding remove linkage for:" + checkSum);
			linkage.setCreator((String)rc.get("user-agent.parameters.sr_user_key"), (String)rc.get("user-agent.parameters.sr_user"));
			linkage.setCreateDate(Calendar.getInstance());
			listArticles.add(linkage);
		}
		
		return (ArrayList<Linkage>) listArticles;
	}
	
	/*
	 * Populate linkage objects for the Linking Request
	 */
	private ArrayList<Linkage> generateLinkageListForRequestContextAdd(RequestContext rc) throws HandlerException{
		
		if (mapIMDocID==null) { mapIMDocID = new HashMap<Integer,String>(); };
		mapIMDocID.clear();
		if (mapIMGUID==null) { mapIMGUID = new HashMap<Integer,String>(); };
		mapIMGUID.clear();
		if (mapIMVersion==null) { mapIMVersion = new HashMap<String,String>(); };
		mapIMVersion.clear();
		String docURL = null;
		
		ArrayList<Linkage> listArticles = new ArrayList<Linkage>();

		// Process the case link from "Draft content" on InfoCenter CCA page. And process link from linked answer detail page.
		boolean linkedAnswerClickThru = "true".equals(rc.get("isClickLinked"))? true : false;
		if(!isValidString((String)rc.get(INameConstants.CCA_ANSWER_SOLUTIONS_LIST)) || linkedAnswerClickThru) {
			Execution.context().log().event(DEBUG_MSG, DEBUG_MESSAGE, "Process the case link from \"Draft content\" on InfoCenter CCA page. And process link from linked answer detail page:" + rc);

			docURL = (String)rc.get("DocURL");

			if(!isValidString(docURL)) {
				rc.setParam(LINK_UNLINK_RESULT, FAILED + "Error happened when adding case link due to document URL is null");
				Execution.context().log().event(ERROR_MSG, DEBUG_MESSAGE, "Error happened when adding case link due to document URL is null.");
				throw new HandlerException(CCA_INTERNAL_ERROR, new Object[]{new ErrorResponse(CCA_INTERNAL_ERROR, "Error happened when adding case link due to document URL is null")});
			}

			Linkage linkage = new Linkage();

			linkage.setUrl(docURL);
			linkage.setTitle((String)rc.get("DocTitle"));
			linkage.setExcerpt((String)rc.get("DocExcerpt"));
			linkage.setDocumentId((String)rc.get("DocumentID"));
			linkage.setDocumentType((String)rc.get("DocType"));
			linkage.setDocumentGuid((String)rc.get("DocGUID"));
			linkage.setCaseId(srKey);
			
			String userName = (String)rc.get("UserName");
			String userID = rc.getUserAgentRequestParameter(USERID_PARAM);
			linkage.setCreator(userID, userName);

//			docVersion = (String)rc.get("DocVersion");
//			status = ""; // (String)rc.get("Status"); // reserved

			linkage.setCreateDate(Calendar.getInstance());

			listArticles.add(linkage);
		}
		else { // Process the case link from "link a case" on InfoCenter CCA page.
			Execution.context().log().event(DEBUG_MSG, DEBUG_MESSAGE, "Process the case link from \"link a case\" on InfoCenter CCA page:" + rc);
			TIntArrayList answerIdList;
			TIntArrayList docIdList;
			List<CCAAnswerContext> answerContextList;

			//Map imDocVersionMap = new HashMap();
			String imDocId = null;

			answerContextList = (List<CCAAnswerContext>)rc.get("CCA_ANSWER_CONTEXT_LIST");
			if(answerContextList == null) {
				// if the RequestContext does not contain the answer context list, then we need to create it here
				answerIdList = new TIntArrayList();
				docIdList = new TIntArrayList();
				CCAHandler.parseSolutionsList((String)rc.get(INameConstants.CCA_ANSWER_SOLUTIONS_LIST), answerIdList, docIdList);
				answerContextList = CCAHandler.getResultContext(rc, answerIdList, docIdList);
			}

			for(int i = 0 ; i < answerContextList.size() ; i++) {
				CCAAnswerContext context = (CCAAnswerContext)answerContextList.get(i);
				
				int docID = context.getDocId();
				String strUrl = context.buildExternalURL();
				debugMsg("Going to populate IMVersMap with:" + (String)rc.get("DocumentID") + " with DocVersion:" + (String)rc.get("DocVersion"));
				
				populateIMVersMap((String)rc.get("DocumentID"), (String)rc.get("DocVersion"));
				
				populateIMValMap(docID, strUrl);
				
				imDocId = mapIMDocID.get(docID);
				
				String imVersion = !StringUtils.isEmpty(imDocId) ? mapIMVersion.get(imDocId) : null;
				
				debugMsg("Got imVersion:" + imVersion);
				
				String imGuid = !StringUtils.isEmpty(imDocId) ? mapIMGUID.get(docID) : null;
				debugMsg("Got imGuid:" + imGuid);
				
				Linkage linkage = new Linkage();

				linkage.setUrl(strUrl);
				linkage.setTitle(context.getTitle());
				linkage.setExcerpt(context.getExcerpt());
				// linkage.setDocumentId(StringUtils.isEmpty(imDocId) ? Integer.toString(context.getDocId()) : imDocId);
				linkage.setDocumentId(StringUtils.isEmpty(imDocId) ? "" : imDocId);
				//linkage.setDocumentId(docURL);
				linkage.setVersion(StringUtils.isEmpty(imVersion) ? "" : imVersion);
				linkage.setDocumentGuid(StringUtils.isEmpty(imGuid) ? "" : imGuid);
				
				String strGUID = StringUtils.isEmpty(imGuid) ? getKey(context) : imGuid;
				if (strGUID==null) { strGUID=""; }
				
				linkage.setDocumentType(context.getDoctype());
				linkage.setCaseId(srKey);
			
				String userName = (String)rc.get("UserName");
				String userID = rc.getUserAgentRequestParameter(USERID_PARAM);
				linkage.setCreator(userID, userName);
				linkage.setCreateDate(Calendar.getInstance());

				listArticles.add(linkage);
			}
		}
		
		return listArticles;
		
	}
	
	/*
	 * Generate a map of IM Document Version values
	 */
	private void populateIMVersMap(String strIdList, String strVersList) {
		debugMsg("IDLIST:" + strIdList + "   VER.LIST:" + strVersList);
		
		if (StringUtils.isEmpty(strIdList)) { 
			return; 
		}
		
		String[] listIds = StringUtils.split(strIdList, ";");
		String[] listVers = StringUtils.split(strVersList, ",");
		
		for (int i=0; i<listIds.length; i++) {
			debugMsg("Mapping " + listIds[i] + " to " + listVers[i]);
			String versNumber = StringUtils.split(listVers[i],":")[1];
			debugMsg("Mapping " + listIds[i] + " to " + versNumber);
			mapIMVersion.put(listIds[i], versNumber);
		}
	}
	
	/*
	 * Generate a map of IM Values from the execution context
	 */
	private void populateIMValMap(int docId, String docUrl) throws HandlerException {
		ContentSourceType type = null;
		CollectionDataStore cds = null;
		String collectionName = Execution.context().getUDS().getCollectionName(ContentStoreHelper.getUniqueIdCollection(docId));
		int collectionId = Execution.context().getUDS().getCollectionId(collectionName);
		try {
			cds = Execution.context().getUDS().getCollectionDataStore(collectionId);
			type = cds.getContentSourceType(docId);
		}
		catch(Exception ex) {
			Execution.context().log().event(ERROR_MSG, DEBUG_MESSAGE, ex);
			errorMsg("Error populating IM Value Map:" + ex.getMessage());
			throw new HandlerException("GET_DOC_TYPE", null, ex, new ErrorResponse("GET_DOC_TYPE", "There were errors getting doc type"));
		}
		
		if (type!=null && cds != null && type.equals(ContentSourceType.IM)) {
			int numStartTemp = (docUrl.indexOf("&id=") > -1) ? docUrl.indexOf("&id=") + 4 : -1;
			int numEndTemp = (docUrl.indexOf("&", numStartTemp) > -1) ? docUrl.indexOf("&", numStartTemp) : docUrl.length();
			if(numStartTemp >= 0) {
				mapIMDocID.put(docId, docUrl.substring(numStartTemp, numEndTemp).replaceAll("S:", ""));
				mapIMGUID.put(docId, cds.getGuid(docId));
				debugMsg("Populating IM Value Map with value: " + docId + ":" + cds.getGuid(docId));
			}
		}
	}
	
	/*
	 * Get the Document Key from the Answer Context
	 */
	private String getKey(CCAAnswerContext context) throws HandlerException {
		String guid = null;
		int docId = context.getDocId();
		ContentSourceType type = null;
		CollectionDataStore cds = null;
		String collectionName = Execution.context().getUDS().getCollectionName(ContentStoreHelper.getUniqueIdCollection(docId));
		int collectionId = Execution.context().getUDS().getCollectionId(collectionName);
		try {
			cds = Execution.context().getUDS().getCollectionDataStore(collectionId);
			type = cds.getContentSourceType(docId);
		}
		catch(Exception ex) {
			Execution.context().log().event(ERROR_MSG, ERROR_MESSAGE, ex);
			errorMsg("Error retrieving IM Value Map:" + ex.getMessage());
			throw new HandlerException("GET_DOC_TYPE", null, ex, new ErrorResponse("GET_DOC_TYPE", "There were errors getting doc type"));
		}

		if(type != null && cds != null && type.equals(ContentSourceType.IM)) {
			cds.assureGuids();
			guid = cds.getGuid(docId);
			if(guid == null) {
				errorMsg("GUID returned from IM Value Map is null:" + docId);
				Execution.context().log().event(ERROR_MSG, "NULL_GUID", "The guid is null. Maybe the task classification was not done.");
			}else{
				debugMsg("Successfully returned GUID:" + guid);
			}
		}
		return guid;
	}
	

}
