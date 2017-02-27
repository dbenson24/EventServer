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
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

public class MainVerticle extends AbstractVerticle {

	/**
	 * 
	 */
	public MainVerticle() {
		// TODO Auto-generated constructor stub
	}

	@Override
	public void start() {
		// Create an HTTP server which simply returns "Hello World!" to each request.
		HttpServer server = vertx.createHttpServer();
		Router router = Router.router(vertx);
		Route indexRoute = router.route("/");
		indexRoute.handler(routingContext -> {
			routingContext.response().sendFile("index.html");
		});
		EventBus eb = vertx.eventBus();
		final String chat = "public";
		
		SockJSHandlerOptions options = new SockJSHandlerOptions().setHeartbeatInterval(2000);

		SockJSHandler sockJSHandler = SockJSHandler.create(vertx, options);

		sockJSHandler.socketHandler((SockJSSocket sockJSSocket) -> {

		  final String id = sockJSSocket.writeHandlerID();
		  // Just echo the data back
		  sockJSSocket.handler(sockJSSocket::write);
		  sockJSSocket.endHandler(socket -> {
		  });
		  sockJSSocket.exceptionHandler(socket -> {
		  });
		});
		
		router.route("/events/*").handler(sockJSHandler);
		
		SockJSHandler ebusSockJSHandler = SockJSHandler.create(vertx);

		PermittedOptions inboundPermitted1 = new PermittedOptions().setAddress("chat.public");
		PermittedOptions outboundPermitted1 = new PermittedOptions().setAddress("chat.public");
		BridgeOptions bridgeopts = new BridgeOptions().
				addInboundPermitted(inboundPermitted1).
				addOutboundPermitted(outboundPermitted1);
		ebusSockJSHandler.bridge(bridgeopts);
		
		router.route("/eventbus/*").handler(ebusSockJSHandler);
		
		server.requestHandler(router::accept).listen(8080);
	}
}
