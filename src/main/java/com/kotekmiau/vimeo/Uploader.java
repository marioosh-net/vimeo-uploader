package com.kotekmiau.vimeo;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import javax.activation.MimetypesFileTypeMap;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.net.io.CopyStreamEvent;
import org.apache.commons.net.io.CopyStreamListener;
import org.apache.commons.net.io.Util;
import org.scribe.builder.ServiceBuilder;
import org.scribe.builder.api.VimeoApi;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Token;
import org.scribe.model.Verb;
import org.scribe.model.Verifier;
import org.scribe.oauth.OAuthService;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Vimeo command-line uploader
 * @author marioosh
 */
public class Uploader {

	final static String TOKEN_FILE = ".vimeo-token";
	final static String CONFIG_FILE = ".vimeo-uploader";
	final static String ENDPOINT = "http://vimeo.com/api/rest/v2";
	static boolean saveToken = true;
	static boolean verbose = false;
	static boolean checkStatusOnly = false;
	static String videoPath;
	static String videoTitle;
	static String videoDescription;

	/**
	 * vimeo service - main object
	 */
	private OAuthService service;
	private Token accessToken;	

	@SuppressWarnings("serial")
	public Uploader() throws IOException {

		/**
		 * read .vimeo-uploader
		 */
		FileInputStream in1 = null;
		Properties properties = null;
		try {
			File f = new File(System.getProperty("user.home"), Uploader.CONFIG_FILE);
			properties = new Properties();
			if (f.createNewFile()) {
				properties.put("apiKey", "API_KEY_HERE");
				properties.put("secret", "SECRET_HERE");
				properties.store(new FileOutputStream(f), "vimeo-uploader configuration file");
			}
			in1 = new FileInputStream(f);
			properties.load(in1);

		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			in1.close();
		}

		service = new ServiceBuilder()
			.provider(VimeoApi.class)
			.apiKey(properties
			.getProperty("apiKey"))
			.apiSecret(properties.getProperty("secret"))
			.build();

		/**
		 * try read saved token
		 */
		accessToken = readToken();
		if (accessToken == null) {

			Scanner in = new Scanner(System.in);

			if(verbose) System.out.println("Fetching the Request Token...");
			Token token = service.getRequestToken();
			System.out.println("Got the Request Token!");
			System.out.println();

			if(verbose) System.out.println("Fetching the Authorization URL...");
			String authURL = service.getAuthorizationUrl(token);
			if(verbose) System.out.println("Got the Authorization URL!");
			System.out.println("Now go and authorize Scribe here:");
			System.out.println(authURL+"&permission=write");
			System.out.println("And paste the authorization code here");
			System.out.print(">>");
			Verifier verifier = new Verifier(in.nextLine());
			System.out.println();

			// Trade the Request Token and Verfier for the Access Token
			if(verbose) System.out.println("Trading the Request Token for an Access Token...");
			accessToken = service.getAccessToken(token, verifier);
			if(verbose) System.out.println("Got the Access Token!");
			if(saveToken) {
				saveToken(accessToken);
			}
			if(verbose) { 
				System.out.println("(if your curious it looks like this [token, secret]: " + accessToken + " )");
				System.out.println();
			}
		}

		// get logged username
		Response response = call("vimeo.test.login", null);
		String username = (
				((Node) path(response.getBody(), "//username", XPathConstants.NODE))
				.getTextContent());
		System.out.printf("%-30S: %s\n", "Logged", username);
		
		// get free storage space in bytes		
		response = call("vimeo.videos.upload.getQuota", null);
		String free = ((Node) path(response.getBody(), "//upload_space", XPathConstants.NODE))
				.getAttributes()
				.getNamedItem("free")
				.getNodeValue();
		System.out.printf("%-30S: %s MB\n", "Free Storage Space", Double.parseDouble(free)/1024/1024); 

		if(!checkStatusOnly) {
			
			// Uploading procss
			
			// get a upload ticket
			response = call("vimeo.videos.upload.getTicket", 				
				new HashMap<String, String>() {{
				   put("upload_method", "streaming");
				}}
			);
			NamedNodeMap nodeMap = ((Node) path(response.getBody(), "//ticket", XPathConstants.NODE)).getAttributes();
			final String ticketId = nodeMap.getNamedItem("id").getNodeValue();
			String endpoint = nodeMap.getNamedItem("endpoint").getNodeValue();
			System.out.printf("%-30S: %s\n", "Ticket ID", ticketId);
			System.out.printf("%-30S: %s\n", "Endpoint", endpoint);
			
			/*
			// check ticket
			response = call("vimeo.videos.upload.checkTicket",
					new HashMap<String, String>() {{
						   put("ticket_id", ticketId);
						}}
					);
			NamedNodeMap nodeMap1 = ((Node) path(response.getBody(), "//ticket", XPathConstants.NODE)).getAttributes();
			System.out.printf("%-30S: %s\n", "Ticket VALID", nodeMap1.getNamedItem("valid").getNodeValue());
			*/
	
			// send video
			doPut(endpoint);
			
			// Complete the upload process.
			response = call("vimeo.videos.upload.complete",
				new HashMap<String, String>() {{
				   put("filename", new File(videoPath).getName());
				   put("ticket_id", ticketId);
				}}				
			);
			NamedNodeMap nodeMap2 = ((Node) path(response.getBody(), "//ticket", XPathConstants.NODE)).getAttributes();
			final String videoId = nodeMap2.getNamedItem("video_id").getNodeValue();
			System.out.printf("%-30S: %s\n", "Video ID", videoId);
			
			// set title
			response = call("vimeo.videos.setTitle",
				new HashMap<String, String>() {{
				   put("video_id", videoId);
				   put("title", videoTitle);
				}}				
			);
			NamedNodeMap nodeMap3 = ((Node) path(response.getBody(), "//rsp", XPathConstants.NODE)).getAttributes();
			String stat = nodeMap3.getNamedItem("stat").getNodeValue();
			System.out.printf("%-30S: %s\n", "set Title status", stat);
			
			// set description
			response = call("vimeo.videos.setDescription",
				new HashMap<String, String>() {{
				   put("video_id", videoId);
				   put("description", videoDescription);
				}}				
			);
			NamedNodeMap nodeMap4 = ((Node) path(response.getBody(), "//rsp", XPathConstants.NODE)).getAttributes();
			String stat2 = nodeMap4.getNamedItem("stat").getNodeValue();
			System.out.printf("%-30S: %s\n", "set Description status", stat2);
			
			System.out.printf("DONE.\n\n");
		}
	}
	
	/**
	 * call method
	 * @param method
	 * @param params
	 * @return
	 */
	private Response call(String method, Map<String, String> params) {
		if(verbose)	System.out.println("Calling method: \""+method+"\"");
		OAuthRequest orequest = new OAuthRequest(Verb.GET, Uploader.ENDPOINT);
		orequest.addQuerystringParameter("method", method);
		if(params != null) {
			for(Map.Entry<String, String> p: params.entrySet()) {
				orequest.addQuerystringParameter(p.getKey(), p.getValue());
			}
		}
		service.signRequest(accessToken, orequest);
		Response response = orequest.send();
		if(verbose) System.out.println(response.getBody());
		/*
		NodeList nodes = (NodeList) path(response.getBody(), "//username", XPathConstants.NODESET);
		for (int i = 0; i < nodes.getLength(); i++) {
			System.out.println(nodes.item(i).getTextContent());
		}
		*/
		return response;
	}

	/**
	 * parse xml response
	 * @param content
	 * @param path
	 * @param returnType
	 * @return
	 */
	private Object path(String content, String path, QName returnType) {
		try {
			DocumentBuilderFactory domFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = domFactory.newDocumentBuilder();
			Document doc = builder.parse(new InputSource(new StringReader(content)));
			XPath xpath = XPathFactory.newInstance().newXPath();
			XPathExpression expr = xpath.compile(path);
			return expr.evaluate(doc, returnType);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
	
	/**
	 * upload file (streaming)
	 * @param endpoint
	 */
	private void doPut(String endpoint) {
		URL endpointUrl;
		HttpURLConnection connection;
		try {
			File videoFile = new File(videoPath);
			endpointUrl = new URL(endpoint);
			connection = (HttpURLConnection) endpointUrl.openConnection();
			connection.setRequestMethod("PUT");
			connection.setRequestProperty("Content-Length", videoFile.length()+"");
			connection.setRequestProperty("Content-Type", new MimetypesFileTypeMap().getContentType(videoFile));
			connection.setFixedLengthStreamingMode((int) videoFile.length());
			connection.setDoOutput(true);
			
			CopyStreamListener listener = new CopyStreamListener() {
				public void bytesTransferred(long totalBytesTransferred, int bytesTransferred, long streamSize) {
					System.out.printf("\r%-30S: %d / %d (%d %%)", "Sent", totalBytesTransferred, streamSize, totalBytesTransferred*100/streamSize);
				}
				public void bytesTransferred(CopyStreamEvent event) {
				}
			};
			InputStream in = new FileInputStream(videoFile);
			OutputStream out = connection.getOutputStream();
			System.out.println("Uploading \""+videoFile.getAbsolutePath()+"\"... ");
			long c = Util.copyStream(in, out, Util.DEFAULT_COPY_BUFFER_SIZE, videoFile.length(), listener);
			System.out.printf("\n%-30S: %d\n", "Bytes sent", c);
			in.close();
			out.close();
			
			// return code
			System.out.printf("\n%-30S: %d\n", "Response code", connection.getResponseCode());

		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * save token in file
	 * @param token
	 * @return
	 */
	private boolean saveToken(Token token) {
		File f = new File(System.getProperty("user.home"), Uploader.TOKEN_FILE);
		try {
			f.createNewFile();
			if (f.exists()) {
				if(verbose) System.out.print("Saving token \"" + token + "\" to \"" + f.getAbsolutePath() + "\" ... ");
				FileWriter w = new FileWriter(f);
				BufferedWriter writer = new BufferedWriter(w);
				writer.write(token.getToken() + ";" + token.getSecret());
				writer.close();
				if(verbose) System.out.println("DONE");
			}
			return true;
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
		return false;
	}

	/**
	 * read token from file
	 * @return access token
	 */
	private Token readToken() {
		File f = new File(System.getProperty("user.home"), Uploader.TOKEN_FILE);
		if (f.exists()) {
			if(verbose) System.out.println("Reading token from \"" + f.getAbsolutePath() + "\"");
			try {
				FileReader r = new FileReader(f);
				BufferedReader br = new BufferedReader(r);
				String token1 = br.readLine();
				Token token = new Token(token1.split(";")[0], token1.split(";")[1]);
				return token;
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}
		return null;
	}

	/**
	 * MAIN
	 * read input parameters and options
	 * 
	 * @param args
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {
		try {
			Options options = new Options();
			options.addOption("ns", false, "no save token");
			options.addOption("h", false, "help");
			options.addOption("v", false, "be verbose");
			options.addOption("t", true, "video title");
			options.addOption("d", true, "video description");
			options.addOption("s", false, "check status only (don't upload)");

			CommandLineParser parser = new PosixParser();
			CommandLine cmd;

			cmd = parser.parse(options, args);

			if (cmd.hasOption("h") || (cmd.getArgs().length < 1 && !cmd.hasOption("s"))) {
				HelpFormatter formatter = new HelpFormatter();
				formatter.printHelp("java -jar vimeo-uploader.jar [options] <video file>", options);
				System.out.println("");
				return;
			}
			if (cmd.hasOption("ns")) {
				saveToken = false;
			}
			if (cmd.hasOption("v")) {
				verbose = true;
			}
			if(cmd.hasOption("s")) {
				checkStatusOnly = true;
			} else {
				if (cmd.hasOption("t")) {
					videoTitle = cmd.getOptionValue("t");
				} else {
					videoTitle = new File(cmd.getArgs()[0]).getName();
				}
				if (cmd.hasOption("d")) {
					videoDescription = cmd.getOptionValue("d");
				} else {
					videoDescription = new SimpleDateFormat("dd.MM.yyyy - hh:mm").format(new Date());
				}				
				videoPath = cmd.getArgs()[0];
			}
		
			new Uploader();
			
		} catch (ParseException e) {
			System.out.println(e.getMessage());
		}
		
	}
	
	private void verbose(Object o) {
		if(verbose) {
			System.out.println(o.toString());
		}
	}

}
