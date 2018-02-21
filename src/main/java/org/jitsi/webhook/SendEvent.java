package org.jitsi.webhook;

import java.util.ArrayList;
import java.util.Map;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.jitsi.util.Logger;

public class SendEvent implements Runnable {
	
	/**
	 * The logger used by this instance.
	 */
	private final static Logger logger = Logger.getLogger(SendEvent.class);

	/**
	 * WebHook URL
	 */
	public static String WEBHOOK_URL = null;

	/**
	 * Data send to api
	 */
	private Map<String, Object> data;

	/**
	 * Constructor
	 * 
	 * @param Map<String, Object> data
	 */
	public SendEvent(Map<String, Object> data) {
		this.data = data;
	}

	/**
	 * Exec thread
	 */
	public void run() {
		if (SendEvent.WEBHOOK_URL != null) {
			try {
				this.sendPost();
			} catch (Exception e) {
				logger.info("Exception while trying to send event", e);
			}
		} else {
			logger.error("Webhook API is null");
		}
	}

	/**
	 * Send post request
	 * 
	 * @throws Exception
	 */
	private void sendPost() throws Exception {
		CloseableHttpClient client = HttpClients.createDefault();
		HttpPost httpPost = new HttpPost(SendEvent.WEBHOOK_URL);

		ArrayList<BasicNameValuePair> parameters = new ArrayList<BasicNameValuePair>();
		for (Map.Entry<String, Object> entry : this.data.entrySet()) {
			parameters.add(new BasicNameValuePair(entry.getKey(), entry.getValue().toString()));
		}
		httpPost.setEntity(new UrlEncodedFormEntity(parameters));
		client.execute(httpPost);
		client.close();
	}
}
