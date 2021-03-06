package com.apigee.sdk.apm.android;


import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings.Secure;
import android.util.Log;

import com.apigee.sdk.ApigeeClient;
import com.apigee.sdk.AppIdentification;
import com.apigee.sdk.DefaultAndroidLog;
import com.apigee.sdk.Logger;
import com.apigee.sdk.apm.android.crashlogging.CrashManager;
import com.apigee.sdk.apm.android.metrics.LowPriorityThreadFactory;
import com.apigee.sdk.apm.android.model.App;
import com.apigee.sdk.apm.android.model.ApplicationConfigurationModel;
import com.apigee.sdk.apm.android.model.ClientLog;
import com.apigee.sdk.data.client.DataClient;


public class MonitoringClient implements SessionTimeoutListener {

	public static final boolean DEFAULT_AUTO_UPLOAD_ENABLED = true;
	public static final boolean DEFAULT_CRASH_REPORTING_ENABLED = true;
	
    public static final int SUBMIT_THREAD_TTL_MILLIS = 180 * 1000;
	public static final int SESSION_EXPIRATION_MILLIS = 1000 * 60 * 30;
	
	private static MonitoringClient singleton = null;

	private Handler sendMetricsHandler;
	private HttpClient httpClient;
	private HttpClient originalHttpClient;

	private MetricsCollectorService collector;
	private CompositeConfigurationServiceImpl loader;
	private MetricsUploadService uploadService;
	private DefaultAndroidLog defaultLogger;
	private AndroidLog log;
	
	private ArrayList<UploadListener> listListeners;

	private AppIdentification appIdentification;
	private Context appActivity;
	
	private boolean isActive;
	private boolean isInitialized = false;
	
	private boolean enableAutoUpload;
	private boolean crashReportingEnabled;
	
	private SessionManager sessionManager;
	
	private DataClient dataClient;
	
    private static ThreadPoolExecutor sExecutor =
            new ThreadPoolExecutor(0, 1, SUBMIT_THREAD_TTL_MILLIS, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), new LowPriorityThreadFactory());


	public static final int UPLOAD_INTERVAL = 300000; // 5 Minutes

	
	public static synchronized MonitoringClient initialize(AppIdentification appIdentification,
			DataClient dataClient,
			Context appActivity,
			MonitoringOptions monitoringOptions) throws InitializationException {

		// HttpClient defaultClient = AndroidHttpClient.newInstance(appId);
		return initialize(appIdentification, dataClient, appActivity, new DefaultHttpClient(), monitoringOptions);
	}

	public static synchronized MonitoringClient initialize(AppIdentification appIdentification,
			DataClient dataClient,
			Context appActivity, HttpClient client,
			MonitoringOptions monitoringOptions)
	throws InitializationException {
		if (singleton == null) {
			try {
				MonitoringClient instance = new MonitoringClient(appIdentification, dataClient,
						appActivity, client, monitoringOptions);
				
				singleton = instance;
				return instance;
			} catch (InitializationException e) {
				Log.e(ClientLog.TAG_MONITORING_CLIENT, "Cannot instantiate MonitoringClient:" + e.getMessage());
				throw e;
			} catch (Throwable t) {
				t.printStackTrace();
				String message;
				if( (t != null) && (t.getMessage() != null) ) {
					message = t.getMessage();
				} else {
					message = "unknown";
				}
				Log.e(ClientLog.TAG_MONITORING_CLIENT, "exception caught:" + message);
				return null;
			}
		} else {
			Log.e(ClientLog.TAG_MONITORING_CLIENT, "MonitoringClient is already initialized");
			throw new InitializationException("MonitoringClient is already initialized");
		}
	}

	public static MonitoringClient getInstance() {
		if (singleton != null) {
			return singleton;
		} else {
			// throw new
			// LoadConfigurationException("Android HttpClientWrapper not initialized");
			//Log.w(ClientLog.TAG_MONITORING_CLIENT,
			//"Android HttpClientWrapper not initialized. Returning null");
			// throw new
			// InitializationException("Monitoring Client was not initialized");

			// Need to change this function to support auto initialization.
			return null;
		}
	}

	/**
	 * @throws InitializationException
	 * 
	 * 
	 * 
	 */
	public MonitoringClient(AppIdentification appIdentification, DataClient dataClient, Context appActivity,
			HttpClient client,
			MonitoringOptions monitoringOptions) throws InitializationException {
		defaultLogger = new DefaultAndroidLog();
		initializeInstance(appIdentification, dataClient, appActivity, client, monitoringOptions);
	}

	protected void initializeInstance(AppIdentification appIdentification, DataClient dataClient, Context appActivity,
			HttpClient client,
			MonitoringOptions monitoringOptions) throws InitializationException {

		this.isActive = false;
		this.isInitialized = false;

		this.listListeners = new ArrayList<UploadListener>();

		if( monitoringOptions != null ) {
			this.crashReportingEnabled = monitoringOptions.getCrashReportingEnabled();
			this.enableAutoUpload = monitoringOptions.getEnableAutoUpload();

			UploadListener uploadListener = monitoringOptions.getUploadListener();
			
			if( uploadListener != null ) {
				if (null == this.listListeners) {
					this.listListeners = new ArrayList<UploadListener>();
				}
				this.listListeners.add(uploadListener);
			}
		} else {
			this.crashReportingEnabled = true;
			this.enableAutoUpload = true;
		}
		
		this.dataClient = dataClient;
		
		this.sessionManager = new SessionManager(SESSION_EXPIRATION_MILLIS,this);
		this.sessionManager.openSession();

		// First configure the logger

		this.appIdentification = appIdentification;

		this.originalHttpClient = client;
		
		this.appActivity = appActivity;
		
		
		if (readUpdateAndApplyConfiguration(client,enableAutoUpload,null))
		{
			if (enableAutoUpload)
			{
				Log.i(ClientLog.TAG_MONITORING_CLIENT, "Enabling auto sending of analytics data ");
			} else {
				Log.i(ClientLog.TAG_MONITORING_CLIENT, "Auto sending of analytics data disabled");
			}
			
			log.i(ClientLog.TAG_MONITORING_CLIENT, ClientLog.EVENT_INIT_AGENT);
			
			isInitialized = true;
		} else {
			isInitialized = false;
			isActive = false;
		}
	}
	
	public Logger getLogger() {
		return log;
	}
	
	synchronized protected void initializeSubServices()
	{
		log = new AndroidLog(loader);
		
		collector = new MetricsCollector2(loader);

		httpClient = new HttpClientWrapper(originalHttpClient, appIdentification, collector, loader);
					
		this.uploadService = new UploadService(this, appActivity, appIdentification,
				log, collector, loader, sessionManager);
	}
	
	private boolean allowedToSendData()
	{
		boolean willSendData = false;
		
		ApplicationConfigurationService configService = this.getApplicationConfigurationService();
		
		if (null != configService) {
			App compositeAppConfigModel =
					configService.getCompositeApplicationConfigurationModel();
			if (null != compositeAppConfigModel) {
				boolean monitoringDisabled = 
						compositeAppConfigModel.getMonitoringDisabled() != null && 
						compositeAppConfigModel.getMonitoringDisabled();
		
				if (monitoringDisabled)
				{
					Log.i(ClientLog.TAG_MONITORING_CLIENT, "Monitoring disabled in configuration. Not sending data");
					return false;
				}
			}
		
			ApplicationConfigurationModel configurations = configService.getConfigurations();
		
			if ((null != configurations) && (configurations.getSamplingRate() != null))
			{
				Long sampleRate = configurations.getSamplingRate();
			
				Random generator = new Random();
			
				int coinflip = generator.nextInt(100);
			
				if (coinflip < sampleRate.intValue())
				{
					Log.i(ClientLog.TAG_MONITORING_CLIENT, "Monitoring enabled. Sample Rate : " + sampleRate);
					willSendData = true;
				} else {
					Log.i(ClientLog.TAG_MONITORING_CLIENT, "Monitoring disabled. Sample Rate :  "  + sampleRate);
					willSendData = false;
				}
			} else {
				Log.i(ClientLog.TAG_MONITORING_CLIENT, "Monitoring Enabled ");
				willSendData = true;
			}
		}
		
		return willSendData;
	}
	
	public String getBaseServerURL() {
		String baseServerURL = null;
		String baseURL = appIdentification.getBaseURL();
		
		if( baseURL.endsWith("/") ) {
			baseServerURL = baseURL +
					appIdentification.getOrganizationId() +
					"/" +
					appIdentification.getApplicationId();
		} else {
			baseServerURL = baseURL +
					"/" +
					appIdentification.getOrganizationId() +
					"/" +
					appIdentification.getApplicationId();
		}
		
		return baseServerURL;
	}
	
	public String getConfigDownloadURL() {
		return getBaseServerURL() + "/apm/apigeeMobileConfig";
	}
	
	public String getCrashReportUploadURL(String crashFileName) {
		return getBaseServerURL() + "/apm/crashLogs/" + crashFileName;
	}
	
	public String getMetricsUploadURL() {
		return getBaseServerURL() + "/apm/apmMetrics";
	}

	public void resumeAgent() {
		if (isInitialized && !isActive) {
			log.i(ClientLog.TAG_MONITORING_CLIENT, ClientLog.EVENT_RESUME_AGENT);
			isActive = true;
			sessionManager.resume();
		}
	}

	public void pauseAgent() {
		if (isInitialized && isActive) {
			log.i(ClientLog.TAG_MONITORING_CLIENT, ClientLog.EVENT_PAUSE_AGENT);
			//uploadService.uploadData();
			//sExecutor.execute(new UploadDataTask());
			isActive = false;
			sessionManager.pause();
		}
	}
	
	public String putOrPostString(String httpMethod, String body, String urlAsString, String contentType) {
		String response = null;
		OutputStream out = null;
		InputStream in = null;
		
		try {
			URL url = new URL(urlAsString);
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
	    
			byte[] putOrPostData = body.getBytes();

			conn.setDoOutput(true);
			conn.setRequestMethod(httpMethod);
			conn.setRequestProperty("Content-Length", Integer.toString(putOrPostData.length));
			
			if( contentType != null ) {
				conn.setRequestProperty("Content-Type", contentType);
			}
			
			conn.setUseCaches(false);
			
			if (httpMethod.equals("POST")) {
				Log.v(ClientLog.TAG_MONITORING_CLIENT, "Posting data to '" + urlAsString + "'");
			} else {
				Log.v(ClientLog.TAG_MONITORING_CLIENT, "Putting data to '" + urlAsString + "'");
			}

			out = conn.getOutputStream();
			out.write(putOrPostData);
			out.close();
			out = null;

			in = conn.getInputStream();
			if( in != null ) {
				BufferedReader reader = new BufferedReader(new InputStreamReader(in));
				StringBuilder sb = new StringBuilder();
				String line;
				
				while( (line = reader.readLine()) != null ) {
					sb.append(line);
					sb.append('\n');
				}
				
				response = sb.toString();
				Log.v(ClientLog.TAG_MONITORING_CLIENT,"response from server: '" + response + "'");
			} else {
				response = null;
				Log.v(ClientLog.TAG_MONITORING_CLIENT,"no response from server after post");
			}
			
			final int responseCode = conn.getResponseCode();
			
			Log.v(ClientLog.TAG_MONITORING_CLIENT,"responseCode from server = " + responseCode);
			
		} catch(Exception e) {
			Log.e(ClientLog.TAG_MONITORING_CLIENT,"Unable to post to '" + urlAsString + "'");
			Log.e(ClientLog.TAG_MONITORING_CLIENT,e.getLocalizedMessage());
			response = null;
		} finally {
			try {
				if( out != null ) {
					out.close();
				}
			
				if( in != null ) {
					in.close();
				}
			} catch(Exception ignored) {
			}
		}
		
	    return response;		
	}
	
	public String postString(String postBody, String urlAsString, String contentType) {
		return putOrPostString("POST", postBody, urlAsString, contentType);
	}

	public String postString(String postBody, String urlAsString) {
		return postString(postBody, urlAsString, "application/json; charset=utf-8");
	}

	public String putString(String body, String urlAsString, String contentType) {
		return putOrPostString("PUT", body, urlAsString, contentType);
	}

	public String putString(String body, String urlAsString) {
		return putString(body, urlAsString, "application/json; charset=utf-8");
	}

	public boolean readUpdateAndApplyConfiguration(HttpClient client,
								boolean enableAutoUpload,
								ConfigurationReloadedListener reloadListener) {
		boolean success = true;
		loader = new CompositeConfigurationServiceImpl(appActivity,
														appIdentification,
														this.dataClient,
														this,
														client);
		
		initializeSubServices();
		
		try {
			// loader.loadConfigurations(this.appId);
			boolean loadSuccess = loader.loadLocalApplicationConfiguration();
			
			if(loadSuccess)
			{
				Log.v(ClientLog.TAG_MONITORING_CLIENT, "Found previous configuration on disk. ");
			} else {
				Log.v(ClientLog.TAG_MONITORING_CLIENT, "No configuration found on disk. Using default configurations");
			}

			if (allowedToSendData())
			{
				isActive = true;
			} else {
				isActive = false;
			}
			
			if (isActive)
			{ 
				if (crashReportingEnabled)
				{
					sExecutor.execute(new CrashManagerTask(this));
					sExecutor.execute(new ForcedUploadDataTask(this));
				}
				else
				{
					Log.i(ClientLog.TAG_MONITORING_CLIENT, "Crash reporting disabled");
				}
			}
			
			// read configuration
			sExecutor.execute(new LoadRemoteConfigTask(reloadListener));
			
			if (enableAutoUpload)
			{
				initiateSendLoop();
			}
			
		} catch (LoadConfigurationException e) {
			success = false;
		} catch (RuntimeException e) {
			success = false;
		} catch (Throwable t) {
			success = false;
		}
		
		return success;
	}

	
	// initiate Send Loop is done from the main thread. The logic should be:
	/*
	 * 1. Check to see that the configuration is loaded
	 * 2. Check to see if there is already an task on the thread queue to upload data
	 * 3. If not, put data onto the thread queue
	 */
	private void initiateSendLoop() {

		if (null != sendMetricsHandler) {
			sendMetricsHandler.removeMessages(0);
		} else {
			sendMetricsHandler = new Handler();
		}

		final MonitoringClient client = this;
		
		Runnable runnable = new Runnable() {

			public void run() {
				
				if(isInitialized && isActive)
				{
					sExecutor.execute(new UploadDataTask(client));
					long uiMillis = loader.getConfigurations().getAgentUploadIntervalInSeconds() * 1000;		
					sendMetricsHandler.postDelayed(this, uiMillis);
				} else {
					Log.i(ClientLog.TAG_MONITORING_CLIENT, "Configuration was not able to initialize. Not initiating analytics send loop");
				}
				
			}
		};

		// Start the automatic sending of data
		sendMetricsHandler.postDelayed(runnable, loader.getConfigurations().getAgentUploadIntervalInSeconds() * 1000);

		Log.v(ClientLog.TAG_MONITORING_CLIENT, "Initiating data to be sent on a regular interval");
	}
	
	public boolean isDeviceNetworkConnected()
	{
		boolean networkConnected = true;  // assume so
		
		try {
			ConnectivityManager connectivityManager = (ConnectivityManager) appActivity
				.getSystemService(Context.CONNECTIVITY_SERVICE);

			NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();

			if (networkInfo != null) {
				networkConnected = networkInfo.isConnected();
			}
	
		} catch( Exception e ) {
		}
		
		return networkConnected;
	}
	
	public boolean uploadAnalytics()
	{
		boolean analyticsUploaded = false;
		if(isInitialized && isActive)
		{
			if( ! sessionManager.isSessionValid() ) {
				sessionManager.openSession();
			}
			
			if( isDeviceNetworkConnected() ) {
				Log.i(ClientLog.TAG_MONITORING_CLIENT, "Manually uploading analytics now");
				sExecutor.execute(new ForcedUploadDataTask(this));
				analyticsUploaded = true;
			} else {
				Log.i(ClientLog.TAG_MONITORING_CLIENT, "uploadAnalytics called, device not connected to network");
			}
		} else {
			Log.i(ClientLog.TAG_MONITORING_CLIENT, "Configuration was not able to initialize. Not initiating analytics send loop");
		}
		
		return analyticsUploaded;
	}
	
	public boolean refreshConfiguration(ConfigurationReloadedListener reloadListener)
	{
	    boolean configurationUpdated = false;
	    
	    if(isInitialized && isActive)
	    {
	        // are we currently connected to network?
	        if( isDeviceNetworkConnected() ) {
	            Log.i(ClientLog.TAG_MONITORING_CLIENT, "Manually refreshing configuration now");
	            configurationUpdated = readUpdateAndApplyConfiguration(this.originalHttpClient,
	            		this.enableAutoUpload,
	            		reloadListener);
	        } else {
	            Log.i(ClientLog.TAG_MONITORING_CLIENT, "refreshConfiguration called, device not connected to network");
	        }
	    } else {
	        Log.i(ClientLog.TAG_MONITORING_CLIENT, "Configuration was not able to initialize. Unable to refresh.");
	    }
	    
	    return configurationUpdated;
	}
	
	public String getApigeeDeviceId()
	{
		String android_id = Secure.getString(
				appActivity.getContentResolver(), Secure.ANDROID_ID);
		
		return android_id;
	}
	
	public void onCrashReportUpload(String crashReport) {
		if (listListeners != null) {
			Iterator<UploadListener> iterator = listListeners.iterator();
			while( iterator.hasNext() ) {
				UploadListener listener = iterator.next();
				listener.onUploadCrashReport(crashReport);
			}
		}
	}
	
	private class LoadRemoteConfigTask implements Runnable {

		private ConfigurationReloadedListener reloadListener;
		
		public LoadRemoteConfigTask(ConfigurationReloadedListener reloadListener) {
			this.reloadListener = reloadListener;
		}
		
		@Override
		public void run() {
			
			boolean newConfigsExist = loader.synchronizeConfig();
			if(newConfigsExist)
			{
				Log.v(ClientLog.TAG_MONITORING_CLIENT, "Found a new configuration - re-initializing sub-services");
				try {
					loader.loadLocalApplicationConfiguration();
					if( this.reloadListener != null )
					{
						this.reloadListener.configurationReloaded();
					}

					if ( allowedToSendData())
					{
						isActive = true;
					} else {
						isActive = false;
					}
				} catch (LoadConfigurationException e) {
					Log.e(ClientLog.TAG_MONITORING_CLIENT, "Error trying to reload application configuration " + e.toString());
				} catch (Throwable t) {
					Log.e(ClientLog.TAG_MONITORING_CLIENT, "Error trying to reload application configuration " + t.toString());
				}
			} else {
				Log.i(ClientLog.TAG_MONITORING_CLIENT, "Remote configuration same as existing configuration OR sychronization failed, hence doing nothing");
			}
		}
	}
	
	/*
	 * Task to be executed in the background
	 */
	private class UploadDataTask implements Runnable {

		private MonitoringClient client;
		
		public UploadDataTask(MonitoringClient client) {
			this.client = client;
		}
		
		@Override
		public void run() {
			
			//this is a bit of a hack to prevent data from being uploaded if there is no data to upload. 
			//this is common if the app has been put into the background
			if (log.haveLogRecords() || collector.haveSamples())
			{
				Log.v(ClientLog.TAG_MONITORING_CLIENT, "There are metrics to send. Sending metrics now");
				List<UploadListener> listListeners = null;
				if( client != null ) {
					listListeners = client.getMetricsUploadListeners();
				}
				uploadService.uploadData(listListeners);
			} else {
				Log.v(ClientLog.TAG_MONITORING_CLIENT, "No metrics to send. Skipping metrics sending");	
			}
		}
		
	}
	
	/*
	 * Task to be executed in the background
	 */
	private class ForcedUploadDataTask implements Runnable {
		private MonitoringClient client;
		
		public ForcedUploadDataTask(MonitoringClient client) {
			this.client = client;
		}
		
		@Override
		public void run() {
			Log.v(ClientLog.TAG_MONITORING_CLIENT, "Sending Metrics via Android Client");
			List<UploadListener> listListeners = null;
			if( client != null ) {
				listListeners = client.getMetricsUploadListeners();
			}
			uploadService.uploadData(listListeners);
		}
		
	}
	
	/*
	 * Task to be executed in the background
	 */
	private class CrashManagerTask implements Runnable {
		private MonitoringClient client;

		public CrashManagerTask(MonitoringClient client) {
			this.client = client;
		}
		
		@Override
		public void run() {

			if(CrashManager.appIdentification == null)
			{
				CrashManager.register(appActivity, log, appIdentification, client);
			}
		}
		
	}
	

	/**
	 * @return the httpClient
	 */
	public HttpClient getHttpClient() {
		if (isInitialized && isActive) {
			return httpClient;
		} else {
			return originalHttpClient;
		}
	}

	/**
	 * This method instruments http clients that is passed. This method is
	 * useful when there is very complex initialization of the HTTP Client or if
	 * the HTTP Client is a custom HTTP client
	 * 
	 * @return the httpClient
	 */
	public HttpClient getInstrumentedHttpClient(HttpClient client) {

		if (isInitialized && isActive)
			return new HttpClientWrapper(client, appIdentification, collector, loader);
		else
			return client;
	}

	public Logger getAndroidLogger() {
		if ((log != null) && isInitialized && isActive) {
			return log;
		} else {
			return defaultLogger;
		}
	}

	public void setUploadService(MetricsUploadService uploadService) {
		this.uploadService = uploadService;
	}

	public MetricsUploadService getUploadService() {
		return uploadService;
	}

	public ApplicationConfigurationService getApplicationConfigurationService() {
		return loader;
	}

	public MetricsCollectorService getMetricsCollectorService() {
		return collector;
	}
	
	public boolean isInitialized() {
		return isInitialized;
	}
	
	public void onUserInteraction() {
		if( isInitialized ) {
			if( !isActive ) {
				isActive = true;
				if( sessionManager != null ) {
					sessionManager.resume();
				}
			}
			
			if( sessionManager != null ) {
				if( sessionManager.isSessionValid() ) {
					//Log.v(ClientLog.TAG_MONITORING_CLIENT,"updating session activity time");
					sessionManager.onUserInteraction();
				} else {
					Log.d(ClientLog.TAG_MONITORING_CLIENT,"creating new session");
					sessionManager.openSession();
				}
			}
		}
	}

	public void onSessionTimeout(String sessionUUID,Date sessionStartTime,Date sessionLastActivityTime) {
		log.flush();
		collector.flush();
		//android.util.Log.i(ClientLog.TAG_MONITORING_CLIENT,"notified that session timed out");
		
		if( isInitialized && isActive )
		{
			// start a new session
			sessionManager.openSession();
		}
	}
	
	public synchronized boolean addMetricsUploadListener(UploadListener metricsUploadListener) {
		boolean listenerAdded = false;
		if( this.listListeners != null ) {
			this.listListeners.add(metricsUploadListener);
			listenerAdded = true;
		}
		
		return listenerAdded;
	}
	
	public synchronized boolean removeMetricsUploadListener(UploadListener metricsUploadListener) {
		boolean listenerRemoved = false;
		if( this.listListeners != null ) {
			listenerRemoved = this.listListeners.remove(metricsUploadListener);
		}
		
		return listenerRemoved;
	}
	
	public ArrayList<UploadListener> getMetricsUploadListeners() {
		return this.listListeners;
	}

	public static String getDeviceModel() {
		return Build.MODEL;
	}
	
	public static String getDeviceType() {
		return Build.TYPE;
	}
	
	public static String getDeviceOSVersion() {
		return Build.VERSION.RELEASE;
	}
	
	public static String getDevicePlatform() {
		return ApigeeClient.SDK_TYPE;
	}
	
	public static String getSDKVersion() {
		return ApigeeClient.SDK_VERSION;
	}

}
