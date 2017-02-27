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
		vertx.createHttpServer().requestHandler(req -> req.response().end("Hello World from MainVerticle!")).listen(8080);
	}
}
