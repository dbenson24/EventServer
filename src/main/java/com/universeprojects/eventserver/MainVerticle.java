/**
 * File: MainVerticle.java
 * Date: Feb 26, 2017
 * Author: Derek
 * Email: Derek.Benson@tufts.edu
 * Description:
 * TODO
 *
 */
package com.universeprojects.eventserver;


import java.util.ArrayList;
import java.util.List;

import io.netty.util.internal.logging.InternalLoggerFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.*;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.SharedData;
import io.vertx.ext.auth.AuthProvider;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.AuthHandler;
import io.vertx.ext.web.handler.BasicAuthHandler;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.sockjs.BridgeEvent;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.sstore.LocalSessionStore;

public class MainVerticle extends AbstractVerticle {
	HttpClient client;
	SharedData sd;
	EventBus eb;
	Logger logger;
	/**
	 * 
	 */
	public MainVerticle() {
		// TODO Auto-generated constructor stub
	}

	@Override
	public void start() {
		logger = LoggerFactory.getLogger("MainVerticle");
		
		HttpClientOptions options = new HttpClientOptions().
				setDefaultHost("test-dot-playinitium.appspot.com").setDefaultPort(443).setSsl(true);
		client = vertx.createHttpClient(options);
		HttpServer server = vertx.createHttpServer();
		Router router = Router.router(vertx);
		
		Route indexRoute = router.route("/");
		indexRoute.handler(routingContext -> {
			routingContext.addCookie(Cookie.cookie("test", "Hello!"));
			routingContext.response().sendFile("index.html");
		});
		
		sd = vertx.sharedData();
		eb = vertx.eventBus();
		String[] chats = {
				"public",
				"group",
				"party",
				"location",
				"private",
				"notifications"
		};
		BridgeOptions bridgeopts = new BridgeOptions();
		for (String chat : chats) {
			// handle clients sending in messages
			eb.consumer("chat."+chat+".in", message -> {
				System.out.println("I have received a message: " + message.body());
				String uid = message.headers().get("userId");
				JsonObject body = (JsonObject) message.body();
				body.put("channel", chat);
				body.put("accountId", uid);
				// body is a json object that represents 
				// what the clients send through the websocket
				// in addition to a channel field and the accountId
				formatChatMsg(body, resp -> {
					if (resp.succeeded()) {
						// get JSON payload returned from app engine service
						JsonObject jsonResp = resp.result();
						DeliveryOptions opts = new DeliveryOptions();
						if (jsonResp.containsKey("id")) {
							opts.addHeader("id", jsonResp.getString("id"));
						}
						if (jsonResp.containsKey("payload")) {
							eb.publish("chat."+chat+".out", jsonResp.getJsonObject("payload"), opts);
							saveMessage(chat, jsonResp);
						}
					}
				});
			});
			PermittedOptions inboundPermitted = new PermittedOptions().setAddress("chat."+chat+".in");
			PermittedOptions outboundPermitted = new PermittedOptions().setAddress("chat."+chat+".out");
			bridgeopts.addInboundPermitted(inboundPermitted);
			bridgeopts.addOutboundPermitted(outboundPermitted);
		}
		
		eb.consumer("chat.sendSaved", message -> {
			JsonObject body = (JsonObject)message.body();
			logger.info(body.encode());
			String socketId = body.getString("socketId");
			String address = body.getString("address");
			String userId = body.getString("userId");
			Long time = body.getLong("date");
			JsonArray msgsJson = new JsonArray();
			JsonArray msgs = null;
			switch(address) {
			case "chat.private.out":
				msgs = sd.<String, JsonArray>getLocalMap("messages").get(userId + "#private");
				break;
			case "chat.public.out":
				msgs = sd.<String,JsonArray>getLocalMap("messages").get("publicChat");
				break;
			case "chat.group.out":
				msgs = sd.<String,JsonArray>getLocalMap("messages").get(sd.<String, String>getLocalMap("groups").get(userId));
				break;
			case "chat.location.out":
				msgs = sd.<String,JsonArray>getLocalMap("messages").get(sd.<String, String>getLocalMap("locations").get(userId));
				break;
			case "chat.party.out":
				msgs = sd.<String,JsonArray>getLocalMap("messages").get(sd.<String, String>getLocalMap("parties").get(userId));
				break;
			}
			if(msgs != null) {
				msgs.forEach(msg -> {
					if (((JsonObject) msg).getLong("createdDate") > time) {
						msgsJson.add(msg);
					}
				});
			}
			sendSocketMessage(address, socketId, msgsJson);
		});
		
		SockJSHandler ebusSockJSHandler = SockJSHandler.create(vertx);
		ebusSockJSHandler.bridge(bridgeopts, be -> {
			JsonObject msg = be.getRawMessage();
			if (msg != null) {
				logger.info(be.getRawMessage().encode());
			}
			switch(be.type()) {
			// Client attempts to Send
			case SEND: {
				System.out.println("Handling EventBus Socket Message Send");
				String userId = getSocketUserId(be);
				if (userId != null) {
					// Associate the send message with the Socket's user
					JsonObject headers = new JsonObject().put("userId", userId);
					msg.put("headers", headers);
					be.setRawMessage(msg);
					be.complete(true);
				} else {
					// Deny the send if no user is associated with the Socket
					be.complete(false);
				}
			}	break;
			// Client attempts to Publish
			case PUBLISH:
				System.out.println("Handling EventBus Socket Message Publish");
				// Prevent websocket clients from publishing
				be.complete(false);
				break;
			// Message goes out from Server to Client
			case RECEIVE: {
				System.out.println("Handling EventBus Socket Message Recieve");
				String userId = getSocketUserId(be);
				if (userId != null) {
					System.out.println("userid: " + userId);
					be.complete(filterChat(be, userId));
				} else {
					System.out.println("Recieving User is null");
					be.complete(false);
				}
			}	break;
			// Client attempts to register
			case REGISTER: {
				System.out.println("Handling EventBus Socket Message Register");
				String userId = getSocketUserId(be);
				JsonObject savedMessagesBody = new JsonObject();
				savedMessagesBody.put("address", msg.getString("address"));
				savedMessagesBody.put("userId", userId);
				savedMessagesBody.put("socketId", be.socket().writeHandlerID());
				if (msg.getJsonObject("headers").containsKey("Last-Recieved")) {
					savedMessagesBody.put("date", msg.getJsonObject("headers").getLong("Last-Recieved"));
				} else {
					savedMessagesBody.put("date", 0);
				}
				if (userId != null) {
					System.out.println("Socket is already authenticated!");
					be.complete(true);
					eb.send("chat.sendSaved", savedMessagesBody);
				} else {
					// Uses the Headers sent with the register request for authentication
					// Specifically it looks for Auth-Token
					authenticate(be.getRawMessage().getJsonObject("headers"), res -> {
						if (res.succeeded()) {
							JsonObject user = res.result();
							System.out.println("AuthUser: " + user.encode());
							logger.info("AuthUser: " + user.encode());
							// Caches the userId for this socket so it doesn't hit the server for subsequent registers
							sd.getLocalMap("sockets").put(be.socket().writeHandlerID(), user.getString("accountId"));
							logger.info(be.socket().writeHandlerID());
							be.complete(true);
							eb.send("chat.sendSaved", savedMessagesBody);
						} else {
							logger.info("Unable to authenticate " + res.cause());
							res.cause().printStackTrace();
							be.complete(false);
						}
					});
				}
			}	break;
			// Client attempts to unregister
			case UNREGISTER:
				System.out.println("Handling EventBus Socket Message Unregister");
				be.complete(true);
				break;
			case SOCKET_CLOSED: {
				System.out.println("Handling EventBus Socket Message Socket_Closed");
				// Cleanup the userId associated with the socket
				String userId = getSocketUserId(be);
				if (userId != null) {
					sd.getLocalMap("sockets").remove(userId);
					System.out.println("Socket authentication was removed!");
				}
				be.complete(true);
			}	break;
			case SOCKET_CREATED:
				System.out.println("Handling EventBus Socket Message Socket_Created");
				be.complete(true);
				break;
			default:
				System.out.println("Handling EventBus Socket Message Unknown_Type");
				break;
			}
		});
		
		//AuthHandler basicAuthHandler = BasicAuthHandler.create(authProvider);
		//router.route("/eventbus/*").handler(basicAuthHandler);
		router.route("/eventbus/*").handler(ebusSockJSHandler);

		server.requestHandler(router::accept).listen(6969, "0.0.0.0");
	}
	
	private void authenticate(JsonObject authInfo, Handler<AsyncResult<JsonObject>> resultHandler) {
		if (authInfo.containsKey("Auth-Token")) {
			HttpClientRequest request = client.post("/eventserver?type=auth", response -> {
				response.bodyHandler(respBody -> {
					try {
					JsonObject body = respBody.toJsonObject();
					if (body.getBoolean("success")) {
						String id = body.getString("accountId");
						SharedData sd = vertx.sharedData();
						sd.getLocalMap("locations").put(id, body.getString("locationId"));
						sd.getLocalMap("groups").put(id, body.getString("groupId"));
						sd.getLocalMap("parties").put(id, body.getString("partyId"));
						resultHandler.handle(Future.succeededFuture(body));
					} else {
						resultHandler.handle(Future.failedFuture("Auth-Token was rejected by the server"));
					}
					} catch (Exception e) {
						e.printStackTrace();
						resultHandler.handle(Future.failedFuture("Invalid response from server: " + respBody.toString()));
					}
				});
			});
			// TODO: handle http errors
			JsonObject reqbody = new JsonObject();
			reqbody.put("Auth-Token", authInfo.getString("Auth-Token"));
			request.exceptionHandler(err -> {
				System.out.println("Recieved exception: " + err.getMessage());
				resultHandler.handle(Future.failedFuture("Authentican Request failed"));
			});
			request.putHeader("content-type", "application/json");
			String raw = reqbody.encode();
			request.putHeader("content-length", Integer.toString(raw.length()));
			request.write(raw);
			request.end();
		} else {
			resultHandler.handle(Future.failedFuture("Auth-Token was not provided"));
		}
		
	}
	
	private void formatChatMsg(JsonObject reqbody, Handler<AsyncResult<JsonObject>> resultHandler) {
		HttpClientRequest request = client.post("/eventserver?type=message", response -> {
			response.bodyHandler(respBody -> {
				try {
				JsonObject body = respBody.toJsonObject();
				if (body.getBoolean("success")) {;
					resultHandler.handle(Future.succeededFuture(body));
				} else {
					resultHandler.handle(Future.failedFuture("Chat Message was rejected by server"));
				}
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
		});
		// TODO: handle http errors
		request.exceptionHandler(err -> {
			System.out.println("Recieved exception: " + err.getMessage());
			resultHandler.handle(Future.failedFuture("Message Format Request failed"));
		});
		request.putHeader("content-type", "application/json");
		String raw = reqbody.encode();
		request.putHeader("content-length", Integer.toString(raw.length()));
		request.write(raw);
		request.end();
	}
	
	// returns true if the message should be sent, returns false if the message should not be sent
	private boolean filterChat(BridgeEvent be, String userId) {
		JsonObject msg = be.getRawMessage();
		JsonObject body = msg.getJsonObject("body");
		JsonObject headers = msg.getJsonObject("headers");
		// clear headers
		msg.put("headers", new JsonObject());
		be.setRawMessage(msg);
		switch(msg.getString("address")) {
		case "chat.location.out":
			if (headers.getString("id").equals(sd.getLocalMap("locations").get(userId))) {
				return true;
			} else {
				return false;
			}
		case "chat.group.out":
			if (headers.getString("id").equals(sd.getLocalMap("groups").get(userId))) {
				return true;
			} else {
				return false;
			}
		case "chat.party.out":
			if (headers.getString("id").equals(sd.getLocalMap("parties").get(userId))) {
				return true;
			} else {
				return false;
			}
		case "chat.private.out": {
			String[] players = headers.getString("id").split("/");
			if (players.length != 2) {
				return false;
			}
			if (players[0].equals(userId) || players[1].equals(userId)) {
				return true;
			} else {
				return false;
			}
		}
		case "chat.public.out":
			return true;
		default:
			return false;
		}
	}
	
	private String getSocketUserId(BridgeEvent be) {
		SharedData sd = vertx.sharedData();
		return sd.<String,String>getLocalMap("sockets").get(be.socket().writeHandlerID());
	}
	
	private void sendSocketMessage(String address, String handlerId, JsonObject body) {
		JsonObject message = new JsonObject().put("type", "rec");
		message.put("address", address);
		message.put("body", body);
		Buffer buff = Buffer.buffer();
		buff.appendString(message.encode());
		eb.publish(handlerId, buff);
	}
	private void sendSocketMessage(String address, String handlerId, JsonArray body) {
		JsonObject message = new JsonObject().put("type", "rec");
		message.put("address", address);
		message.put("body", body);
		Buffer buff = Buffer.buffer();
		buff.appendString(message.encode());
		eb.publish(handlerId, buff);
	}
	private void saveMessage(String channel, JsonObject serverResp) {
		JsonObject payload = serverResp.getJsonObject("payload");
		String id;
		JsonArray msgs;
		switch(channel) {
			case "public":
				id = "publicChat";
				msgs = sd.<String, JsonArray>getLocalMap("messages").get(id);
				if (msgs == null) {
					msgs = new JsonArray();
				}
				msgs.add(payload);
				if (msgs.size() > 200) {
					msgs.remove(0);
				}
				sd.getLocalMap("messages").put(id, msgs);
				break;
			case "private":
				id = serverResp.getString("id");
				if (id == null) {
					return;
				}
				String sender, receiver;
				sender = id.split("/")[1] + "#private";
				receiver = id.split("/")[0] + "#private";
				System.out.println("Sender/Receiver - " + sender + "/" + receiver);
				// Save message to sender
				msgs = sd.<String, JsonArray>getLocalMap("messages").get(sender);
				if (msgs == null) {
					msgs = new JsonArray();
				}
				msgs.add(payload);
				if (msgs.size() > 200) {
					msgs.remove(0);
				}
				sd.getLocalMap("messages").put(sender, msgs);
				// Save message to receiver
				if (sender.equals(receiver)) {
					return;
				}
				msgs = sd.<String, JsonArray>getLocalMap("messages").get(receiver);
				if (msgs == null) {
					msgs = new JsonArray();
				}
				msgs.add(payload);
				if (msgs.size() > 200) {
					msgs.remove(0);
				}
				sd.getLocalMap("messages").put(receiver, msgs);
				break;
			default:
				id = serverResp.getString("id");
				logger.info("Getting " + channel+ " messages for ID: " + id);
				msgs = sd.<String, JsonArray>getLocalMap("messages").get(id);
				if (msgs == null) {
					msgs = new JsonArray();
				}
				msgs.add(payload);
				if (msgs.size() > 200) {
					msgs.remove(0);
				}
				sd.getLocalMap("messages").put(id, msgs);
		}
	}
}
