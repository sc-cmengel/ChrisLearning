
package com.inquira.custom.cca.helpers;

import com.inquira.request.cca.CCAHandler;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import org.apache.commons.lang.StringUtils;


/**
 * This class is used to hold information about the linkage of an article between InQuira and Titan.
 */
public class Linkage {

	/** This member holds the creation date of the linkage. */
	private Calendar m_calCreation;

	/** This member holds the modification date of the linkage. */
	private Calendar m_calModification;

	/** This member holds the user name associated with the creation of the linkage. */
	private String m_strCreatorUser = "";

	/** This member holds the user key associated with the creation of the linkage. */
	private String m_strCreatorUserKey = "";

	/** This member holds the document GUID associated with the document (IM articles only). */
	private String m_strDocumentGuid = "";

	/** This member holds the document id associated with the document (IM articles only). */
	private String m_strDocumentId = "";

	/** This member holds the document type associated with the document. */
	private String m_strDocumentType = "";

	/** This member holds the excerpt from the search of when the article was linked. */
	private String m_strExcerpt = "";

	/** This member holds the key element, which is the document URL checksum. */
	private String m_strKey = "";

	/** This member holds the user name associated with the modification of the linkage. */
	private String m_strModifierUser = "";

	/** This member holds the user key associated with the modification of the linkage. */
	private String m_strModifierUserKey = "";

	/** This member holds the title of the article. */
	private String m_strTitle = "";

	/** This member holds the URL of the article. */
	private String m_strUrl = "";
	
	/** This member holds a copy of the case identifier. */
	private String m_strCaseId = "";
	
	/** This member holds the version number. (IM Documents Only) */
	private String m_strVersion = "";
	
	/** This member holds the InQuira status. */
	private String m_strStatus = "";
	
	/** This member holds the link's checksum. */
	private String m_strChecksum = "";
	

	private Calendar dateAsStringFmt(String strDate, DateFormat formatter) {
		Date date;
		try {
	    	date = (Date)formatter.parse(strDate); 
	    	Calendar cal=Calendar.getInstance();
	    	cal.setTime(date);
	    	return cal;
	    } catch (Exception e) {
	    	System.out.println(e.getMessage() + "\n");
	    	return null;
	    }
	}

	private Calendar dateAsString(String strLongDate) {
		/* remove decimal values and normalize the time zone definition */
		String strDate = strLongDate.replaceAll("\\.\\d+", "").replaceAll("Z$", "+0:00");
		if (!strDate.matches("[\\+\\-]\\d+\\:\\d{2}$")) { 
			// TODO - determine if it would be better to local timezone for date conversion when no TZ is specified
			strDate = strDate + "+0:00"; 
		}
		DateFormat formatTZ = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ssZ");
	    Calendar cal;
	    cal = dateAsStringFmt(strDate,formatTZ);
/*		DateFormat formatNoTZ = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss");
	    DateFormat formatUTC = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss'Z'");
        if (cal==null) {
	    	cal = dateAsStringFmt(strDate,formatUTC);
	    }
	    if (cal==null) {
	    	cal = dateAsStringFmt(strDate, formatNoTZ);
	    }  
	    **/       // REMOVED ABOVE CODE IN LIEU OF REGULAR EXPRESSIONS
	    return cal;
	}
	
	private String dateToString(Calendar cal) {
		String strDate = null;
		SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ssZ");
		if (cal != null) {
			strDate = formatter.format(cal.getTime());
		} 
		return strDate;
	}
	
	/**
	 * This method returns the creation date of the linkage.
	 */
	public Calendar getCreateDate() {
		return m_calCreation;
	}
	
	/**
	 * This method returns the creation date of the linkage as a String
	 */
	public String getCreateDateString() {
		return dateToString(m_calCreation);
	}
	
	/**
	 * This method returns a nice readable version of the CreateDate value.
	 */
	public String getReadableCreateDate() {
		String strDate = null;
		SimpleDateFormat formatter = new SimpleDateFormat("MM/dd/yyyy hh:mm:ss");
		if (m_calCreation!=null) {
			strDate = formatter.format(m_calCreation.getTime());
		} else {
			strDate = "";
		}
		return strDate;
	}

	/**
	 * This method returns the creator name of the linkage.
	 */
	public String getCreatorUser() {
		return m_strCreatorUser;
	}

	/**
	 * This method returns the creator key of the linkage.
	 */
	public String getCreatorUserKey() {
		return m_strCreatorUserKey;
	}

	/**
	 * This method returns the document GUID associated with the article.
	 */
	public String getDocumentGuid() {
		return m_strDocumentGuid;
	}

	/**
	 * This method returns the document id associated with the article.
	 */
	public String getDocumentId() {
		return m_strDocumentId;
	}

	/**
	 * This method returns the document type associated with the article.
	 */
	public String getDocumentType() {
		return m_strDocumentType;
	}

	/**
	 * This method return the document excerpt of the linkage.
	 */
	public String getExcerpt() {
		return m_strExcerpt;
	}

	/**
	 * This method returns the key associated with the linkage.
	 */
	public String getKey() {
		return m_strKey;
	}

	/**
	 * This method returns the modifier name of the linkage.
	 */
	public String getModifierUser() {
		return m_strModifierUser;
	}

	/**
	 * This method returns the modifier key of the linkage.
	 */
	public String getModifierUserKey() {
		return m_strModifierUserKey;
	}

	/**
	 * This method returns the modification date of the linkage.
	 */
	public Calendar getModifyDate() {
		return m_calModification;
	}
	
	public String getModifyDateString() {
		return dateToString(m_calModification);
	}

	/**
	 * This method return the document title of the linkage.
	 */
	public String getTitle() {
		return m_strTitle;
	}

	/**
	 * This method returns the URL to the article of the linkage.
	 */
	public String getUrl() {
		return m_strUrl;
	}

	/**
	 * This method returns the identifier of the case.
	 */
	public String getCaseId() {
		return m_strCaseId;
	}
	
	public String getVersion() {
		return m_strVersion;
	}
	
	public String getStatus() {
		return m_strStatus;
	}
	
	public String getDocumentChecksum() {
		return m_strChecksum;
	}

	/**
	 * This method sets the creation date of the linkage.
	 * @param calNow Calendar representing the creation date.
	 */
	public void setCreateDate(final Calendar calCreation) {
		m_calCreation = calCreation;
	}
	
	public void setCreateDate(final String strCreation) {
		m_calCreation = dateAsString(strCreation);
	}

	/**
	 * This method sets the creator of the linkage.
	 * @param strUserKey String containing the key, identifying the user.
	 * @param strUser String containing the user name.
	 */
	public void setCreator(final String strUserKey, final String strUser) {
		m_strCreatorUserKey = StringUtils.trimToEmpty(strUserKey);
		m_strCreatorUser = StringUtils.trimToEmpty(strUser);
	}

	/**
	 * This method sets the document GUID associated with the article.
	 * @param strDocumentGuid String representation of the document GUID.
	 */
	public void setDocumentGuid(final String strDocumentGuid) {
		m_strDocumentGuid = StringUtils.trimToEmpty(strDocumentGuid);
	}

	/**
	 * This method sets the document id associated with the article.
	 * @param strDocumentId String representation of the document id.
	 */
	public void setDocumentId(final String strDocumentId) {
		m_strDocumentId = StringUtils.trimToEmpty(strDocumentId != null ? strDocumentId : "");
	}

	/**
	 * This method sets the document type associated with the article.
	 * @param strDocumentType String representation of the document type.
	 */
	public void setDocumentType(final String strDocumentType) {
		m_strDocumentType =  StringUtils.trimToEmpty(strDocumentType != null ? strDocumentType : "");
	}

	/**
	 * This method sets the document excerpt of the linkage.
	 * @param strExcerpt String containing the excerpt of the article that was found in the search.
	 */
	public void setExcerpt(final String strExcerpt) {
		m_strExcerpt = StringUtils.trimToEmpty(strExcerpt);
	}

	/**
	 * This method sets the Url checksum of the linkage.  Note: this value is overridden when setUrl() is called
	 * @param checkSum String containing the checksum based on the URL being set in setUrl()
	 */
	public void setKey(final String checkSum) {
		m_strKey = StringUtils.trimToEmpty(checkSum);
	}

	/**
	 * This method sets the modifier of the linkage.
	 * @param strUserKey String containing the key, identifying the user.
	 * @param strUser String containing the user name.
	 */
	public void setModifier(final String strUserKey, final String strUser) {
		m_strModifierUserKey = StringUtils.trimToEmpty(strUserKey);
		m_strModifierUser = StringUtils.trimToEmpty(strUser);
	}

	/**
	 * This method sets the modification date of the linkage.
	 * @param calNow Calendar representing the modification date.
	 */
	public void setModifyDate(final Calendar calModification) {
		m_calModification = calModification;
	}
	
	public void setModifyDate(final String strModification) {
		m_calModification = dateAsString(strModification);
	}

	/**
	 * This method sets the document title of the linkage.
	 * @param strTitle String containing the article title.
	 */
	public void setTitle(final String strTitle) {
		m_strTitle = StringUtils.trimToEmpty(strTitle);
	}

	/**
	 * This method sets the URL to the article of the linkage.
	 * @param strUrl String containing the URL of the article.
	 */
	public void setUrl(final String strUrl) {
		m_strUrl = StringUtils.trimToEmpty(strUrl);
		setKey(CCAHandler.urlChecksum(m_strUrl));
	}
	
	/**
	 * This method sets the case identifier of the linkage (this is optional for many CrmInteraction objects)
	 * @param strCaseId
	 */
	public void setCaseId(final String strCaseId) {
		m_strCaseId = StringUtils.trimToEmpty(strCaseId);
	}
	
	public void setVersion(final String strVersion) {
		m_strVersion = StringUtils.trimToEmpty(strVersion);
	}
	
	public void setStatus(final String strStatus) {
		m_strStatus = StringUtils.trimToEmpty(strStatus);
	}

	public void setDocumentChecksum(String checkSum) {
		m_strChecksum = StringUtils.trimToEmpty(checkSum);
		
	}


	// used by "equals()"
	private boolean comp(String strA, String strB) {
		System.out.println("Comparing " + strA + " to " + strB);
		if (strA==null && strB==null) { return true; }
		if (strA!=null && strB!=null && strA.equals(strB)) { return true; }
		System.out.println("NOT EQUAL:  " + strA + " vs. " + strB);
		return false;
	}
	
	/**
	 * This method is used to test if two links are the same, we only need to match on "case id" and ("checksum" or "URL") (in that order) since all other values could change.  So we compare in that order.
	 * @param comparee The Linkage object being compared to this one.
	 * @return true if the two links match
	 */
	public boolean equals(Linkage comparee) {
		if (comp(this.getCaseId(), comparee.getCaseId())) {
			// test checksum first
			if (comp(this.getKey(), comparee.getKey())) { return true; }
			if (comp(this.getUrl(), comparee.getUrl())) { return true; }
		}
		return false;		
	}
	
	/**
	 * This method returns a readable string representation of the object.
	 */
	@Override
	public String toString() {
		final StringBuilder strBuffer = new StringBuilder();

		if (StringUtils.isNotBlank(m_strUrl)) {
			strBuffer.append(" URL: ").append(m_strUrl);
		}

		if (StringUtils.isNotBlank(m_strKey)) {
			strBuffer.append(" Key: ").append(m_strKey);
		}

		if (StringUtils.isNotBlank(m_strTitle)) {
			strBuffer.append(" Title: ").append(m_strTitle);
		}

		if (StringUtils.isNotBlank(m_strDocumentId)) {
			strBuffer.append(" ID: ").append(m_strDocumentId);
		}

		if (StringUtils.isNotBlank(m_strDocumentGuid)) {
			strBuffer.append(" GUID: ").append(m_strDocumentGuid);
		}

		if (StringUtils.isNotBlank(m_strDocumentType)) {
			strBuffer.append(" TYPE: ").append(m_strDocumentType);
		}
		
		if (StringUtils.isNotBlank(m_strCreatorUser) || StringUtils.isNotBlank(m_strCreatorUserKey)) {
			strBuffer.append(" Creator: ").append(m_strCreatorUser).append("/").append(m_strCreatorUserKey);
		}

		if (StringUtils.isNotBlank(m_strModifierUser) || StringUtils.isNotBlank(m_strModifierUserKey)) {
			strBuffer.append(" Modifier: ").append(m_strModifierUser).append("/").append(m_strModifierUserKey);
		}

		return strBuffer.toString();
	}
	
}



