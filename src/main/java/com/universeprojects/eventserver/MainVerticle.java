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

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.*;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServer;
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
	/**
	 * 
	 */
	public MainVerticle() {
		// TODO Auto-generated constructor stub
	}

	@Override
	public void start() {
		Logger logger = LoggerFactory.getLogger("MainVerticle");

		HttpClientOptions options = new HttpClientOptions().setDefaultHost("localhost").setDefaultPort(8080);
		client = vertx.createHttpClient(options);
		HttpServer server = vertx.createHttpServer();
		Router router = Router.router(vertx);
		
		Route indexRoute = router.route("/");
		indexRoute.handler(routingContext -> {
			routingContext.addCookie(Cookie.cookie("test", "Hello!"));
			routingContext.response().sendFile("index.html");
		});
		
		SharedData sd = vertx.sharedData();
		EventBus eb = vertx.eventBus();
		
		eb.consumer("chat.public.in", message -> {
			System.out.println("I have received a message: " + message.body());
			String uid = message.headers().get("userId");
			JsonObject body = (JsonObject) message.body();
			formatChatMsg(body.getString("content"), "public", uid, resp -> {
				if (resp.succeeded()) {
					JsonObject fmtMsg = resp.result();
					JsonObject out = new JsonObject().put("content", fmtMsg.getString("formattedMsg"));
					eb.publish("chat.public.out", out);
				}
			});
		});
		eb.consumer("chat.location.in", message -> {
			System.out.println("I have received a message: " + message.body());
			String uid = message.headers().get("userId");
			JsonObject body = (JsonObject) message.body();
			formatChatMsg(body.getString("content"), "location", uid, resp -> {
				if (resp.succeeded()) {
					JsonObject fmtMsg = resp.result();
					JsonObject out = new JsonObject().put("content", fmtMsg.getString("formattedMsg")).
													  put("id", fmtMsg.getString("id"));
					eb.publish("chat.location.out", out);
				}
			});
		});
		
		SockJSHandler ebusSockJSHandler = SockJSHandler.create(vertx);

		PermittedOptions inboundPermitted1 = new PermittedOptions().setAddress("chat.public.in");
		PermittedOptions inboundPermitted2 = new PermittedOptions().setAddress("chat.location.in");
		PermittedOptions outboundPermitted1 = new PermittedOptions().setAddress("chat.public.out");
		PermittedOptions outboundPermitted2 = new PermittedOptions().setAddress("chat.location.out");
		BridgeOptions bridgeopts = new BridgeOptions().
				addInboundPermitted(inboundPermitted1).
				addInboundPermitted(inboundPermitted2).
				addOutboundPermitted(outboundPermitted1).
				addOutboundPermitted(outboundPermitted2);
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
					JsonObject headers = new JsonObject().put("userId", userId);
					msg.put("headers", headers);
					be.setRawMessage(msg);
					be.complete(true);
				} else {
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
					JsonObject body = msg.getJsonObject("body");
					System.out.println("userid: " + userId);
					switch(msg.getString("address")) {
					case "chat.location.out":
						if (body.getString("id").equals(sd.getLocalMap("locations").get(userId))) {
							be.complete(true);
						} else {
							be.complete(false);
						};
						break;
					case "chat.public.out":
						be.complete(true);
						break;
					default:
						be.complete(false);
					}
				} else {
					System.out.println("Recieving User is null");
					be.complete(false);
				}
			}	break;
			// Client attempts to register
			case REGISTER: {
				System.out.println("Handling EventBus Socket Message Register");
				String userId = getSocketUserId(be);
				if (userId != null) {
					System.out.println("Socket is already authenticated!");
					be.complete(true);
				} else {
					authenticate(be.getRawMessage().getJsonObject("headers"), res -> {
						if (res.succeeded()) {
							JsonObject user = res.result();
							System.out.println("AuthUser: " + user.encode());
							logger.info("AuthUser: " + user.encode());
							sd.getLocalMap("sockets").put(be.socket().writeHandlerID(), user.getString("accountId"));
							be.complete(true);
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

		server.requestHandler(router::accept).listen(6969);
	}
	
	private void authenticate(JsonObject authInfo, Handler<AsyncResult<JsonObject>> resultHandler) {
		if (authInfo.containsKey("Auth-Token")) {
			HttpClientRequest request = client.post("/Event-Servlet-Initium/eventserver?type=auth", response -> {
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
	
	private void formatChatMsg(String msg, String channel, String userId, Handler<AsyncResult<JsonObject>> resultHandler) {
		HttpClientRequest request = client.post("/Event-Servlet-Initium/eventserver?type=message", response -> {
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
		JsonObject reqbody = new JsonObject();
		reqbody.put("channel", channel);
		reqbody.put("contents", msg);
		reqbody.put("accountId", userId);
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
	
	private String getSocketUserId(BridgeEvent be) {
		SharedData sd = vertx.sharedData();
		return sd.<String,String>getLocalMap("sockets").get(be.socket().writeHandlerID());
	}
}
