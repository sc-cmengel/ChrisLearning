package com.inquira.request.cca;

import com.inquira.imws.IMWSClient;
import com.inquira.imws.IMWSException;
import com.inquira.infra.Execution;
import com.inquira.log.LogWriter;
import com.inquira.request.ErrorResponse;
import com.inquira.request.HandlerException;
import com.inquira.request.NamedHandler;
import com.inquira.request.RequestContext;
import com.inquira.siebel.common.webservice.SiebelLinkedAnswerWSClient;
import com.inquira.siebel.common.webservice.WSClientException;
import com.inquira.util.xml.XMLUtil;
import gnu.trove.TIntArrayList;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import org.apache.commons.lang.StringEscapeUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Node;

public class CCASiebelHandler
  implements CCASolutionHandler, NamedHandler, ILogConstants
{
  private static final String __ident = "$Revision: 1.18 $";
  private String siebelServerLocation = "";

  private String siebelUserName = "";

  private String siebelPassword = "";
  private Properties connectionProps;
  private IMWSClient imws = null;
  private static final String siebelMessageTemplatePrefix = "<m:Inquira_spcLink_spcUnlink_spcAdapter_Execute_Input xmlns:m=\"SiebelInQuira\">\n<m0:InquiraRequest xmlns:m0=\"http://www.siebel.com/xml/Inquira%20Sync%20IO\">\n<m0:SRNumber>SRKEY</m0:SRNumber>\n";
  private static final String siebelMessageTemplateSuffix = "</m0:InquiraRequest>\n</m:Inquira_spcLink_spcUnlink_spcAdapter_Execute_Input>";
  private static final String inquiraSolutionTemplate = "<m0:InquiraAnswers>\n<m0:Key>KEY</m0:Key>\n<m0:URL>DOCURL</m0:URL>\n<m0:CaseLinkKey>CLK</m0:CaseLinkKey>\n<m0:Excerpt>EXCERPT</m0:Excerpt>\n<m0:LinkStatus>LS</m0:LinkStatus>\n<m0:Title>TITLE</m0:Title>\n<m0:UserName>USERNAME</m0:UserName>\n<m0:VersionNumber>VERSIONNUMBER</m0:VersionNumber>\n<m0:DocumentID>DOCUMENTID</m0:DocumentID>\n<m0:LinkedDate>LINKEDDATE</m0:LinkedDate>\n<m0:Status>STATUS</m0:Status>\n<m0:DocumentType>DOCUMENTTYPE</m0:DocumentType>\n</m0:InquiraAnswers>\n";
  private static final String inquiraUnlinkSolutionTemplate = "<m0:InquiraAnswers>\n<m0:Key>KEY</m0:Key>\n<m0:LinkStatus>LINKSTATUS</m0:LinkStatus>\n</m0:InquiraAnswers>\n";
  private static final String IMDOCUMENTID_SPERATOR = ";";
  private static final String LINK_UNLINK_RESULT = "linkUnlinkResult";
  private static final String SUCCESS = "[success]";
  private static final String FAILED = "[failed]";
  private static final String SKIP = "[skip]";
  private static final String LINK = "link";
  private static final String UNLINK = "unlink";
  private static final String NULL_STRING = "null";
  private static final String DEBUG_MESSAGE = "DEBUG_MESSAGE";
  protected boolean forceExternal = false;

  private StringBuffer imdocids = new StringBuffer();

  public void init(String url, String username, String password, Properties properties) throws HandlerException
  {
    this.siebelServerLocation = url;
    this.siebelUserName = username;
    this.siebelPassword = password;
  }

  public RequestContext handle(RequestContext rc)
    throws HandlerException
  {
    if (rc.getType().equals("CCAAddSolution")) {
      if (rc.get("ui_mode").equals("remove_solution")) {
        return handleRemoveSolutionExt(rc);
      }

      return handleAddSolution(rc);
    }

    if (rc.getType().equals("CCARemoveSolution"))
    {
      handleRemoveSolutionExt(rc);
    }

    return rc;
  }

  private void doRollback(RequestContext rc, String state)
    throws HandlerException
  {
    Execution.context().log().event(0, "DEBUG_MESSAGE", "Begin to do rollback...");
    try {
      if ("unlink".equals(state)) {
        if (!removeCaseLink(rc)) {
          rc.setParam("linkUnlinkResult", "[failed]Fail to remove case link.");
          Execution.context().log().event(0, "DEBUG_MESSAGE", "(Rollback) Fail to remove case link.");
          throw new HandlerException("ImInternalError", new Object[] { new ErrorResponse("ImInternalError", "Fail to remove case link.") });
        }

      }
      else if (!addCaseLink(rc)) {
        rc.setParam("linkUnlinkResult", "[failed]Fail to add case link.");
        throw new HandlerException("ImInternalError", new Object[] { new ErrorResponse("ImInternalError", "Fail to remove case link.") });
      }

    }
    catch (Exception e1)
    {
      Execution.context().log().event(0, "DEBUG_MESSAGE", "Error happened when doing rollback.");
      throw new HandlerException("ImInternalError", null, e1, new ErrorResponse("ImInternalError", "Error happened when doing rollback."));
    }

    Execution.context().log().event(0, "DEBUG_MESSAGE", "Finish rollback.");
  }

  private boolean checkResult(String result) {
    if (isValidString(result)) {
      int index1 = result.indexOf("<VALUE>") + "<VALUE>".length();
      int index2 = result.indexOf("</VALUE>");
      String valueText = result.substring(index1, index2);
      int index3 = valueText.indexOf("CDATA[") + "CDATA[".length();
      int index4 = valueText.indexOf("]", index3);
      int value = Integer.parseInt(valueText.substring(index3, index4));
      if (1 == value) {
        return true;
      }
    }
    return false;
  }

  public RequestContext handleRemoveSolutionExt(RequestContext rc) throws HandlerException
  {
    String key = null;
    String output = null;
    boolean isUnlinkedFailed = false;
    int[] siebelConnectRetryTimeArr = { 10, 100, 500 };

    String srNumber = (String)rc.get("CCASRKey");
    if (!isValidString(srNumber))
    {
      srNumber = rc.getUserAgentRequestParameter("sr_key");
    }
    if (!isValidString(srNumber)) {
      rc.setParam("linkUnlinkResult", "[failed]Error happened when remove case link due to srNumber (Siebel service request number) is null");
      Execution.context().log().event(0, "DEBUG_MESSAGE", "Error happened when remove case link due to srNumber (Siebel service request number) is null");
      throw new HandlerException("SiebelInternalError", new Object[] { new ErrorResponse("SiebelInternalError", "Error occured when remove case link due to srNumber (Siebel service request number) is null") });
    }

    try
    {
      if (!removeCaseLink(rc)) {
        rc.setParam("linkUnlinkResult", "[failed]Fail to remove case link.");
        Execution.context().log().event(0, "DEBUG_MESSAGE", "Fail to remove case link.");
        throw new HandlerException("ImInternalError", new Object[] { new ErrorResponse("ImInternalError", "Fail to remove case link.") });
      }
    }
    catch (Exception e) {
      rc.setParam("linkUnlinkResult", "[failed]Error happened when removing case link from InfoManager side.");
      Execution.context().log().event(0, "DEBUG_MESSAGE", "Error happened when removing case link from InfoManager side.");
      throw new HandlerException("ImInternalError", null, e, new ErrorResponse("ImInternalError", "Error happened when removing case link from InfoManager side."));
    }

    StringBuffer solutionsXML = new StringBuffer();

    String checkedAnswers = (String)rc.get("CCACheckedAnswersList");
    StringTokenizer st = new StringTokenizer(checkedAnswers, ";");
    while (st.hasMoreElements()) {
      String checkSum = (String)st.nextElement();
      String temp = "<m0:InquiraAnswers>\n<m0:Key>KEY</m0:Key>\n<m0:LinkStatus>LINKSTATUS</m0:LinkStatus>\n</m0:InquiraAnswers>\n";
      temp = temp.replace("KEY", checkSum);
      temp = temp.replace("LINKSTATUS", "Unlink");
      solutionsXML.append(temp);
    }

    String requestXML = "<m:Inquira_spcLink_spcUnlink_spcAdapter_Execute_Input xmlns:m=\"SiebelInQuira\">\n<m0:InquiraRequest xmlns:m0=\"http://www.siebel.com/xml/Inquira%20Sync%20IO\">\n<m0:SRNumber>SRKEY</m0:SRNumber>\n";
    requestXML = requestXML.replace("SRKEY", StringEscapeUtils.escapeXml(srNumber));

    requestXML = requestXML + solutionsXML.toString() + "</m0:InquiraRequest>\n</m:Inquira_spcLink_spcUnlink_spcAdapter_Execute_Input>";

    Execution.context().log().event(4, "DEBUG_MESSAGE", "Input XML for Siebel Case Unlink: " + requestXML);

    String targetPoint = this.siebelServerLocation + "?SWEExtSource=WebService&SWEExtCmd=Execute&UserName=" + this.siebelUserName + "&Password=" + this.siebelPassword;
    SiebelLinkedAnswerWSClient client = new SiebelLinkedAnswerWSClient(targetPoint);
    try {
      output = client.linkUnlinkACase(requestXML);
      if (output.equalsIgnoreCase("success")) {
        rc.setParam("linkUnlinkResult", "[success]");
        Execution.context().log().event(2, "DEBUG_MESSAGE", "Succeed to remove case link from Siebel.");
      }
      else {
        isUnlinkedFailed = true;
        Execution.context().log().event(0, "DEBUG_MESSAGE", "Fail to remove case link from Siebel, and return unlinked status [" + output + "] from Siebel.");
      }
    }
    catch (WSClientException e) {
      isUnlinkedFailed = true;
      Execution.context().log().event(0, "DEBUG_MESSAGE", "[WSClientException] Fail to remove case link from Siebel.");
      Execution.context().log().event(0, "DEBUG_MESSAGE", e);
    }

    if (isUnlinkedFailed) {
      for (int i = 0; i < siebelConnectRetryTimeArr.length; i++) {
        try {
          Execution.context().log().event(1, "DEBUG_MESSAGE", "Retry to remove case link from Siebel " + siebelConnectRetryTimeArr[i] + " milliseconds later.");
          Thread.sleep(siebelConnectRetryTimeArr[i]);
          output = client.linkUnlinkACase(requestXML);
          if (output.equalsIgnoreCase("success")) {
            rc.setParam("linkUnlinkResult", "[success]");
            isUnlinkedFailed = false;
            break;
          }
        }
        catch (WSClientException e) {
          Execution.context().log().event(0, "DEBUG_MESSAGE", "[WSClientException] Fail to remove case link from Siebel.");
          Execution.context().log().event(0, "DEBUG_MESSAGE", e);
        }
        catch (InterruptedException ex)
        {
          Execution.context().log().event(0, "DEBUG_MESSAGE", "Current thread is interrupted.");
        }
      }
    }

    if (isUnlinkedFailed)
    {
      Execution.context().log().event(0, "DEBUG_MESSAGE", "Error connect to siebel: " + this.siebelServerLocation);
      rc.setParam("linkUnlinkResult", "[failed]There were errors connecting to the Siebel server.");
      doRollback(rc, "link");

      throw new HandlerException("SiebelInternalError", new Object[] { new ErrorResponse("SiebelInternalError", "Error occured when remove case link from Siebel") });
    }

    return rc;
  }

  public RequestContext handleAddSolution(RequestContext rc)
    throws HandlerException
  {
    String key = null;
    String docURL = null;
    String docTitle = null;
    String docExcerpt = null;
    String linkStatus = null;
    String userName = null;
    String documentID = null;
    String docType = "";
    String docGUID = null;
    String docVersion = null;
    String status = null;

    String output = null;
    boolean isLinkedFailed = false;
    int[] siebelConnectRetryTimeArr = { 10, 100, 500 };

    String defaultDateMask = "MM/dd/yyyy HH:mm:ss";
    String linkedDateMask = rc.get("LinkedDateMask") == null ? defaultDateMask : (String)rc.get("LinkedDateMask");
    DateFormat formatter = null;
    String linkedDate = null;
    Date currentTime = new Date();
    try {
      formatter = new SimpleDateFormat(linkedDateMask);
      linkedDate = formatter.format(currentTime);
      Execution.context().log().event(2, "DEBUG_MESSAGE", "CCA case link date format is changed as " + linkedDateMask);
    }
    catch (IllegalArgumentException e) {
      formatter = new SimpleDateFormat(defaultDateMask);
      linkedDate = formatter.format(currentTime);
      Execution.context().log().event(0, "DEBUG_MESSAGE", "[IllegalArgumentException]Incorrect CCA case link date format [" + linkedDateMask + "], use the default date format [" + defaultDateMask + "]");
      Execution.context().log().event(0, "DEBUG_MESSAGE", e);
    }

    StringBuffer solutionsXML = new StringBuffer();

    String srNumber = (String)rc.get("CCASRKey");
    if (!isValidString(srNumber))
    {
      srNumber = rc.getUserAgentRequestParameter("sr_key");
    }
    if (!isValidString(srNumber)) {
      rc.setParam("linkUnlinkResult", "[failed]Error happened when adding case link due to srNumber (Siebel service request number) is null.");
      Execution.context().log().event(0, "DEBUG_MESSAGE", "Error happened when adding case link due to srNumber (Siebel service request number) is null.");
      throw new HandlerException("SiebelInternalError", new Object[] { new ErrorResponse("SiebelInternalError", "Error happened when adding case link due to srNumber (Siebel service request number) is null") });
    }

    if ((isValidString((String)rc.get("CCAAnswerSolutionsList"))) || (isValidString((String)rc.get("CCAIMOnlySolutionsList")))) {
      try
      {
        if (!addCaseLink(rc)) {
          rc.setParam("linkUnlinkResult", "[failed]Fail to add case link.");
          throw new HandlerException("ImInternalError", new Object[] { new ErrorResponse("ImInternalError", "Fail to add case link.") });
        }
      }
      catch (Exception e) {
        rc.setParam("linkUnlinkResult", "[failed]Error happened when adding case link.");
        Execution.context().log().event(0, "DEBUG_MESSAGE", "Error happened when adding case link.");

        doRollback(rc, "unlink");
        throw new HandlerException("ImInternalError", null, e, new ErrorResponse("ImInternalError", "Error happened when adding case link."));
      }

    }

    boolean linkedAnswerClickThru = "true".equals(rc.get("isClickLinked"));
    if (((!isValidString((String)rc.get("CCAAnswerSolutionsList"))) && (!isValidString((String)rc.get("CCAIMOnlySolutionsList")))) || (linkedAnswerClickThru)) {
      docURL = (String)rc.get("DocURL");

      if (!isValidString(docURL)) {
        rc.setParam("linkUnlinkResult", "[failed]Error happened when adding case link due to document URL is null");
        Execution.context().log().event(0, "DEBUG_MESSAGE", "Error happened when adding case link due to document URL is null.");
        throw new HandlerException("SiebelConnectionError", new Object[] { new ErrorResponse("SiebelConnectionError", "Error happened when adding case link due to document URL is null") });
      }

      key = CCAHandler.urlChecksum(docURL);
      docTitle = (String)rc.get("DocTitle");
      docExcerpt = (String)rc.get("DocExcerpt");
      linkStatus = (String)rc.get("LinkStatus");
      userName = (String)rc.get("UserName");
      documentID = (String)rc.get("DocumentID");
      docType = (String)rc.get("DocType");
      docGUID = (String)rc.get("DocGUID");
      docVersion = (String)rc.get("DocVersion");
      status = "";

      String temp = "<m0:InquiraAnswers>\n<m0:Key>KEY</m0:Key>\n<m0:URL>DOCURL</m0:URL>\n<m0:CaseLinkKey>CLK</m0:CaseLinkKey>\n<m0:Excerpt>EXCERPT</m0:Excerpt>\n<m0:LinkStatus>LS</m0:LinkStatus>\n<m0:Title>TITLE</m0:Title>\n<m0:UserName>USERNAME</m0:UserName>\n<m0:VersionNumber>VERSIONNUMBER</m0:VersionNumber>\n<m0:DocumentID>DOCUMENTID</m0:DocumentID>\n<m0:LinkedDate>LINKEDDATE</m0:LinkedDate>\n<m0:Status>STATUS</m0:Status>\n<m0:DocumentType>DOCUMENTTYPE</m0:DocumentType>\n</m0:InquiraAnswers>\n";
      temp = temp.replace("KEY", key);
      temp = temp.replace("TITLE", StringEscapeUtils.escapeXml(docTitle));
      temp = temp.replace("DOCURL", StringEscapeUtils.escapeXml(docURL));
      temp = temp.replace("DOCUMENTID", isValidString(documentID) ? documentID : "");
      temp = temp.replace("EXCERPT", isValidString(docExcerpt) ? StringEscapeUtils.escapeXml(docExcerpt) : "");
      temp = temp.replace("VERSIONNUMBER", isValidString(docVersion) ? docVersion : "");
      temp = temp.replace("DOCUMENTTYPE", isValidString(docType) ? docType : "");
      temp = temp.replace("STATUS", isValidString(status) ? status : "");
      temp = temp.replace("CLK", isValidString(docGUID) ? docGUID : "");
      temp = temp.replace("LS", "Link");
      temp = temp.replace("USERNAME", isValidString(userName) ? userName : "");
      temp = temp.replace("LINKEDDATE", linkedDate);

      solutionsXML.append(temp);
    }
    else
    {
      Map imDocVersionMap = new HashMap();
      String imDocId = null;
      userName = (String)rc.get("UserName");

      List answerContextList = (List)rc.get("CCA_ANSWER_CONTEXT_LIST");
      if (answerContextList == null)
      {
        TIntArrayList answerIdList = new TIntArrayList();
        TIntArrayList docIdList = new TIntArrayList();
        CCAHandler.parseSolutionsList((String)rc.get("CCAAnswerSolutionsList"), answerIdList, docIdList);
        answerContextList = CCAHandler.getResultContext(rc, answerIdList, docIdList);
      }

      docVersion = (String)rc.get("DocVersion");
      if (isValidString(docVersion)) {
        CCAHandler.getIMDocVersionMap(docVersion, imDocVersionMap);
      }

      for (int i = 0; i < answerContextList.size(); i++) {
        String temp = "<m0:InquiraAnswers>\n<m0:Key>KEY</m0:Key>\n<m0:URL>DOCURL</m0:URL>\n<m0:CaseLinkKey>CLK</m0:CaseLinkKey>\n<m0:Excerpt>EXCERPT</m0:Excerpt>\n<m0:LinkStatus>LS</m0:LinkStatus>\n<m0:Title>TITLE</m0:Title>\n<m0:UserName>USERNAME</m0:UserName>\n<m0:VersionNumber>VERSIONNUMBER</m0:VersionNumber>\n<m0:DocumentID>DOCUMENTID</m0:DocumentID>\n<m0:LinkedDate>LINKEDDATE</m0:LinkedDate>\n<m0:Status>STATUS</m0:Status>\n<m0:DocumentType>DOCUMENTTYPE</m0:DocumentType>\n</m0:InquiraAnswers>\n";
        CCAAnswerContext context = (CCAAnswerContext)answerContextList.get(i);
        Execution.context().log().event(4, "DEBUG_MESSAGE", "prcessing external response context " + context);

        key = CCAHandler.urlChecksum(context.getUrl());
        imDocId = context.getIMDocumentId();

        temp = temp.replace("KEY", StringEscapeUtils.escapeXml(key));
        temp = temp.replace("TITLE", StringEscapeUtils.escapeXml(context.getTitle()));
        temp = temp.replace("DOCURL", StringEscapeUtils.escapeXml(context.buildExternalURL()));
        temp = temp.replace("DOCUMENTID", imDocId == null ? "" : imDocId);
        temp = temp.replace("VERSIONNUMBER", imDocVersionMap.get(imDocId) == null ? "" : (String)imDocVersionMap.get(imDocId));
        temp = temp.replace("DOCUMENTTYPE", docType == null ? "" : context.getDoctype());
        temp = temp.replace("STATUS", "");

        String caseLinkKey = context.getKey();
        if (caseLinkKey != null) {
          temp = temp.replace("CLK", caseLinkKey);
        }
        else {
          temp = temp.replace("CLK", "");
        }

        temp = temp.replace("EXCERPT", StringEscapeUtils.escapeXml(context.buildExternalExcerpt()));

        temp = temp.replace("LINKEDDATE", linkedDate);
        temp = temp.replace("LS", "Link");
        temp = temp.replace("USERNAME", isValidString(userName) ? userName : "");
        solutionsXML.append(temp);
      }
    }
    String requestXML = "<m:Inquira_spcLink_spcUnlink_spcAdapter_Execute_Input xmlns:m=\"SiebelInQuira\">\n<m0:InquiraRequest xmlns:m0=\"http://www.siebel.com/xml/Inquira%20Sync%20IO\">\n<m0:SRNumber>SRKEY</m0:SRNumber>\n";
    requestXML = requestXML.replace("SRKEY", StringEscapeUtils.escapeXml(srNumber));

    requestXML = requestXML + solutionsXML.toString() + "</m0:InquiraRequest>\n</m:Inquira_spcLink_spcUnlink_spcAdapter_Execute_Input>";

    Execution.context().log().event(4, "DEBUG_MESSAGE", "Input XML for Siebel Case Link: " + requestXML);

    String targetPoint = this.siebelServerLocation + "?SWEExtSource=WebService&SWEExtCmd=Execute&UserName=" + this.siebelUserName + "&Password=" + this.siebelPassword;
    SiebelLinkedAnswerWSClient client = new SiebelLinkedAnswerWSClient(targetPoint);
    try
    {
      output = client.linkUnlinkACase(requestXML);
      if (output.equalsIgnoreCase("success")) {
        rc.setParam("linkUnlinkResult", "[success]");
      }
      else {
        isLinkedFailed = true;
        Execution.context().log().event(0, "DEBUG_MESSAGE", "Fail to add case link to Siebel, and return linked status [" + output + "] from Siebel.");
      }
    }
    catch (WSClientException e) {
      isLinkedFailed = true;
      Execution.context().log().event(0, "DEBUG_MESSAGE", "Fail to add case link to Siebel.");
      Execution.context().log().event(0, "DEBUG_MESSAGE", e);
    }

    if (isLinkedFailed) {
      for (int i = 0; i < siebelConnectRetryTimeArr.length; i++) {
        try {
          Execution.context().log().event(1, "DEBUG_MESSAGE", "Retry to add case link to Siebel " + siebelConnectRetryTimeArr[i] + " milliseconds later.");
          Thread.sleep(siebelConnectRetryTimeArr[i]);
          output = client.linkUnlinkACase(requestXML);
          if (output.equalsIgnoreCase("success")) {
            rc.setParam("linkUnlinkResult", "[success]");
            isLinkedFailed = false;
            break;
          }
        }
        catch (WSClientException e) {
          Execution.context().log().event(0, "DEBUG_MESSAGE", "Fail to add case link to Siebel.");
          Execution.context().log().event(0, "DEBUG_MESSAGE", e);
        }
        catch (InterruptedException ex)
        {
          Execution.context().log().event(0, "DEBUG_MESSAGE", "Current thread is interrupted.");
        }
      }
    }

    if (isLinkedFailed)
    {
      Execution.context().log().event(0, "DEBUG_MESSAGE", "Error communicating with siebel: " + this.siebelServerLocation);
      rc.setParam("linkUnlinkResult", "[failed]There were errors connecting to the Siebel server.");
      doRollback(rc, "unlink");

      throw new HandlerException("SiebelInternalError", new Object[] { new ErrorResponse("SiebelInternalError", "Error occured when add case link to Siebel") });
    }

    return rc;
  }

  public void handleRemoveSolution(RequestContext rc) throws HandlerException {
    try {
      if (!removeCaseLink(rc)) {
        rc.setParam("linkUnlinkResult", "[failed]Fail to remove case link.");
        throw new HandlerException("ImInternalError", new Object[] { new ErrorResponse("ImInternalError", "Fail to remove case link.") });
      }
    }
    catch (Exception ex) {
      Execution.context().log().event(0, "DEBUG_MESSAGE", ex);
      throw new HandlerException("SiebelUnlinkError", null, ex, new ErrorResponse("SiebelUnlinkError", "There were erors when unlinking"));
    }
  }

  protected boolean doCaseLink(RequestContext rc, String guid, String documentId, String srNumber, String summary, int iValue, String docTitle, String docUrl)
    throws Exception
  {
    String result = null;
    if ((guid != null) || (isValidString(documentId))) {
      result = addCaseLinkForIMDoc(guid, documentId, srNumber, summary, iValue);
      if (checkResult(result)) {
        if (documentId != null) {
          this.imdocids.append(documentId).append(";");

          rc.put("CCAContentId", this.imdocids.toString());
          Execution.context().log().event(2, "DEBUG_MESSAGE", "Succeed to add case [sr_number:" + srNumber + "] link for document [documentid: " + documentId + ", Title: " + docTitle + " ,ULR: " + docUrl + "] from IM side.");
        }
      }
      else {
        Execution.context().log().event(2, "DEBUG_MESSAGE", "Fail to add case link for document  [documentid: " + documentId + "] from IM side.");
        return false;
      }
    }
    return true;
  }

  protected boolean addCaseLink(RequestContext rc)
    throws Exception
  {
    String srNumber = (String)rc.get("CCASRKey");

    String summary = (String)rc.get("CCACaseDesc");
    String contentIds = (String)rc.get("CCAContentId");

    int iValue = 1;

    Map linkedAnswersByDocIdCountMap = initialLinkedAnswersCount(srNumber);

    List contexts = (List)rc.get("CCA_ANSWER_CONTEXT_LIST");

    String result = null;
    boolean linkedAnswerClickThru = "true".equals(rc.get("isClickLinked"));
    boolean success = true;

    this.imdocids.delete(0, this.imdocids.length());
    String guid;
    String documentId;
    String docTitle;
    String docUrl;
    Iterator i;
    if (null == contentIds) {
      guid = null;
      documentId = null;
      docTitle = null;
      docUrl = null;

      if (linkedAnswerClickThru) {
        docUrl = (String)rc.get("DocURL");
        docTitle = (String)rc.get("DocTitle");
        documentId = (String)rc.get("DocumentID");
        guid = null;

        if (!shouldAddIMCase(linkedAnswersByDocIdCountMap, documentId)) {
          Execution.context().log().event(2, "DEBUG_MESSAGE", "The linking document [documentid: " + documentId + "] is already linked. You need unlink it first, then do the link.");
          return false;
        }
        success = doCaseLink(rc, guid, documentId, srNumber, summary, iValue, docTitle, docUrl);
        if (!success)
          return false;
      }
      else
      {
        for (i = contexts.iterator(); i.hasNext(); ) {
          CCAAnswerContext context = (CCAAnswerContext)i.next();
          guid = null;
          documentId = context.getIMDocumentId();
          docTitle = context.getTitle();
          docUrl = context.getUrl();

          success = doCaseLink(rc, guid, documentId, srNumber, summary, iValue, docTitle, docUrl);
          if (!success)
            return false;
        }
      }
    }
    else
    {
      StringTokenizer st = new StringTokenizer(contentIds, ";");
      while (st.hasMoreElements()) {
        String contentId = (String)st.nextElement();
        String guid = null;
        if ((contentId != null) && (contentId.trim().length() > 0))
        {
          result = addCaseLinkForIMDoc(guid, contentId, srNumber, summary, iValue);
        }

        if (!checkResult(result)) {
          Execution.context().log().event(2, "DEBUG_MESSAGE", "[Rollback] Fail to add case link for document [documentid: " + contentId + "] from IM side.");
          return false;
        }
      }
    }

    return true;
  }

  protected boolean removeCaseLink(RequestContext rc) throws Exception {
    String srNumber = (String)rc.get("CCASRKey");

    String contentIds = (String)rc.get("CCAContentId");

    String ccaIMIDList = (String)rc.get("CCAIMIDList");

    Map linkedAnswersByDocIdCountMap = initialLinkedAnswersCount(srNumber);

    String result = null;

    this.imdocids.delete(0, this.imdocids.length());

    String error1 = "ContentId not found";
    String error2 = "Case Number not found";

    if ((null == contentIds) && (null != ccaIMIDList) && (!"null".equals(ccaIMIDList)) && ("" != ccaIMIDList))
    {
      StringTokenizer st = new StringTokenizer(ccaIMIDList, ";");
      while (st.hasMoreElements()) {
        String documentId = (String)st.nextElement();
        String contentId = null;
        if ((documentId != null) && (documentId.trim().length() > 0))
        {
          if (shouldRemoveIMCase(linkedAnswersByDocIdCountMap, documentId)) {
            try {
              result = removeCaseLinkForIMDoc(contentId, documentId, srNumber);
            }
            catch (IMWSException e)
            {
              String errorMessage = e.getErrorMessage();
              if ((error1.equals(errorMessage)) || (error2.equals(errorMessage)))
              {
                Execution.context().log().event(2, "DEBUG_MESSAGE", "There is not case link for document [documentid: " + documentId + "] from IM side.");
                continue;
              }

              throw e;
            }
          }
          else {
            result = "[skip]";

            if (("[skip]".equals(result)) || (checkResult(result))) {
              if (documentId != null) {
                this.imdocids.append(documentId).append(";");

                rc.put("CCAContentId", this.imdocids.toString());
                if ("[skip]".equals(result))
                  Execution.context().log().event(2, "DEBUG_MESSAGE", "Skipped to remove case link for document [documentid: " + documentId + "] from IM side.");
                else
                  Execution.context().log().event(2, "DEBUG_MESSAGE", "Succeed to remove case link for document [documentid: " + documentId + "] from IM side.");
              }
            }
            else
            {
              Execution.context().log().event(2, "DEBUG_MESSAGE", "Fail to remove case link for document [documentid: " + documentId + "] from IM side.");
              return false;
            }
          }
        }
      }
    }
    else if (null != contentIds) {
      StringTokenizer st = new StringTokenizer(contentIds, ";");
      while (st.hasMoreElements()) {
        String documentId = (String)st.nextElement();
        String contentId = null;
        if ((documentId != null) && (documentId.trim().length() > 0))
        {
          try {
            result = removeCaseLinkForIMDoc(contentId, documentId, srNumber);
          }
          catch (IMWSException e)
          {
            String errorMessage = e.getErrorMessage();
            if ((error1.equals(errorMessage)) || (error2.equals(errorMessage)))
            {
              Execution.context().log().event(2, "DEBUG_MESSAGE", "There is not case link for document [documentid: " + documentId + "] from IM side.");
              continue;
            }

            throw e;
          }

          if (!checkResult(result)) {
            Execution.context().log().event(2, "DEBUG_MESSAGE", "[Rollback] Fail to remove case link for document [documentid: " + documentId + "] from IM side.");
            return false;
          }
          Execution.context().log().event(2, "DEBUG_MESSAGE", "[Rollback] Succeed to remove case link for document [documentid: " + documentId + "] from IM side.");
        }
      }

    }

    return true;
  }

  protected String addCaseLinkForIMDoc(String contentId, String documentId, String caseNumber, String caseDescription, int incidentValue)
    throws Exception
  {
    if (contentId == null)
      contentId = "";
    if (documentId == null)
      documentId = "";
    if (caseNumber == null)
      caseNumber = "";
    if (caseDescription == null) {
      caseDescription = "";
    }
    if (caseNumber.length() == 0) {
      return null;
    }
    String inputXML = "<CASELINK>\n<CONTENTID>contentId</CONTENTID>\n<DOCUMENTID>documentId</DOCUMENTID>\n<CASENUMBER>caseNumber</CASENUMBER>\n<CASEDESCRIPTION>caseDescription</CASEDESCRIPTION>\n<INCIDENTVALUE>incidentValue</INCIDENTVALUE>\n</CASELINK>\n";

    inputXML = inputXML.replace("contentId", XMLUtil.escapeXML(contentId));
    inputXML = inputXML.replace("documentId", URLEncoder.encode(XMLUtil.escapeXML(documentId), "utf-8"));
    inputXML = inputXML.replace("caseNumber", XMLUtil.escapeXML(caseNumber));
    inputXML = inputXML.replace("caseDescription", XMLUtil.escapeXML(caseDescription));
    inputXML = inputXML.replace("incidentValue", String.valueOf(incidentValue));

    Execution.context().log().event(4, "DEBUG_MESSAGE", "Input XML for IM Case Link: " + inputXML);

    String results = getIMWS().addCaseLink(inputXML);

    return results;
  }

  protected String removeCaseLinkForIMDoc(String contentId, String documentId, String caseNumber)
    throws Exception
  {
    if (contentId == null)
      contentId = "";
    if (documentId == null)
      documentId = "";
    if (caseNumber == null) {
      caseNumber = "";
    }
    if (caseNumber.length() == 0) {
      return null;
    }
    String inputXML = "<CASELINK>\n<CONTENTID>contentId</CONTENTID>\n<DOCUMENTID>documentId</DOCUMENTID>\n<CASENUMBER>caseNumber</CASENUMBER>\n</CASELINK>\n";
    inputXML = inputXML.replace("contentId", XMLUtil.escapeXML(contentId));
    inputXML = inputXML.replace("documentId", XMLUtil.escapeXML(documentId));
    inputXML = inputXML.replace("caseNumber", XMLUtil.escapeXML(caseNumber));

    Execution.context().log().event(4, "DEBUG_MESSAGE", "Input XML for IM Case Unlink: " + inputXML);

    String results = getIMWS().removeCaseLink(inputXML);
    return results;
  }

  private IMWSClient getIMWS() throws Exception {
    if (this.imws == null)
      this.imws = new IMWSClient();
    return this.imws;
  }

  protected String buildForcedExternalKey(CCAAnswerContext context)
    throws HandlerException
  {
    return null;
  }

  public String getHandlerName() {
    return "Nokia Siebel CCA";
  }

  private boolean isValidString(String str)
  {
    if ((null == str) || ("null".equalsIgnoreCase(str)) || ("".trim().equals(str))) {
      return false;
    }
    return true;
  }

  protected boolean shouldRemoveIMCase(Map<String, Integer> linkedAnswersByDocIdCountMap, String documentId)
  {
    if (linkedAnswersByDocIdCountMap == null) {
      return true;
    }
    Integer count = (Integer)linkedAnswersByDocIdCountMap.get(documentId);

    if (null == count)
      count = Integer.valueOf(0);
    else {
      linkedAnswersByDocIdCountMap.put(documentId, count = Integer.valueOf(count.intValue() - 1));
    }
    return count.intValue() < 1;
  }

  protected boolean shouldAddIMCase(Map<String, Integer> linkedAnswersByDocIdCountMap, String documentId)
  {
    if (linkedAnswersByDocIdCountMap == null) {
      linkedAnswersByDocIdCountMap = new HashMap();
    }
    Integer count = (Integer)linkedAnswersByDocIdCountMap.get(documentId);
    if (null == count) {
      count = Integer.valueOf(0);
    }

    if ((documentId != null) && (!"".equals(documentId))) {
      linkedAnswersByDocIdCountMap.put(documentId, Integer.valueOf(count.intValue() + 1));
    }
    return count.intValue() < 1;
  }

  private Map<String, Integer> initialLinkedAnswersCount(String srNumber)
  {
    if (null != srNumber) {
      String linkedAnswerTargetPoint = this.siebelServerLocation + "?SWEExtSource=WebService&SWEExtCmd=Execute&UserName=" + this.siebelUserName + "&Password=" + this.siebelPassword;
      try {
        SiebelLinkedAnswerWSClient client = new SiebelLinkedAnswerWSClient(linkedAnswerTargetPoint);
        String linkedAnswersResponseXML = client.getLinkedAnswers(srNumber);

        Map linkedAnswersByDocIdCountMap = new HashMap();
        Document doc = DocumentHelper.parseText(linkedAnswersResponseXML);
        List nodeList = doc.selectNodes("//DocumentId");
        Integer count;
        String documentId;
        if (null != nodeList) {
          count = Integer.valueOf(0);
          documentId = null;
          for (Node n : nodeList) {
            documentId = n.getText();
            count = (Integer)linkedAnswersByDocIdCountMap.get(documentId);
            if (null == count) {
              count = Integer.valueOf(0);
            }
            linkedAnswersByDocIdCountMap.put(documentId, count = Integer.valueOf(count.intValue() + 1));
          }
        }
        return linkedAnswersByDocIdCountMap;
      } catch (WSClientException e) {
        Execution.context().log().event(0, "DEBUG_MESSAGE", e);
      } catch (DocumentException e) {
        Execution.context().log().event(0, "DEBUG_MESSAGE", e);
      }
    }
    return null;
  }
}