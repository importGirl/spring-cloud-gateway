/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.handler;

import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.handler.AbstractHandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.function.Function;

import static org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping.ManagementPortType.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;

/**
 * 继承 AbstractHandlermapping , 切入HandlerMapping解析过程
 * 作用：
 * 		匹配 Route,并返回处理Route 的 FilteringWebHandler
 * 顺序：
 * 		request -> RequestMappingHandlerMapping -> RoutePredicateHandlerMapping -> controller
 * 	路由匹配是在 HandlerMapping 层进行处理的， 不是mvc的filter 或者 Intercepter层
 * @author Spencer Gibb
 */
public class RoutePredicateHandlerMapping extends AbstractHandlerMapping {

	private final FilteringWebHandler webHandler;

	private final RouteLocator routeLocator;

	private final Integer managementPort;

	private final ManagementPortType managementPortType;

	public RoutePredicateHandlerMapping(FilteringWebHandler webHandler,
			RouteLocator routeLocator, GlobalCorsProperties globalCorsProperties,
			Environment environment) {
		this.webHandler = webHandler;
		this.routeLocator = routeLocator;

		this.managementPort = getPortProperty(environment, "management.server.");
		this.managementPortType = getManagementPortType(environment);
		setOrder(1);// RequestMappingHandlerMapping 之后
		setCorsConfigurations(globalCorsProperties.getCorsConfigurations());
	}

	/** managementPort 与 serverPort 是否相同 */
	private ManagementPortType getManagementPortType(Environment environment) {
		// 服务器端口
		Integer serverPort = getPortProperty(environment, "server.");
		if (this.managementPort != null && this.managementPort < 0) {
			return DISABLED;
		}
		return ((this.managementPort == null
				|| (serverPort == null && this.managementPort.equals(8080))
				|| (this.managementPort != 0 && this.managementPort.equals(serverPort)))
						? SAME : DIFFERENT);
	}

	private static Integer getPortProperty(Environment environment, String prefix) {
		return environment.getProperty(prefix + "port", Integer.class);
	}

	/**
	 * 匹配 Route, 并返回处理Route 的FilterWebHandler
	 * @param exchange
	 * @return
	 */
	@Override
	protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
		// don't handle requests on management port if set and different than server port
		// 如果serverPort 和 managementPort 不同， 直接返回
		if (this.managementPortType == DIFFERENT && this.managementPort != null
				&& exchange.getRequest().getURI().getPort() == this.managementPort) {
			return Mono.empty();
		}
		// 设置参数 gatewayHandlerMapper
		exchange.getAttributes().put(GATEWAY_HANDLER_MAPPER_ATTR, getSimpleName());


		return 	// 获取路由(单个)
				lookupRoute(exchange)
				// .log("route-predicate-handler-mapping", Level.FINER) //name this
				.flatMap((Function<Route, Mono<?>>) r -> {
					// 删除 gatewayPredicateRouteAttr 属性
					exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
					if (logger.isDebugEnabled()) {
						logger.debug(
								"Mapping [" + getExchangeDesc(exchange) + "] to " + r);
					}

					// 设置 ServerWebExchangeUtils.gatewayRoute 属性
					exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, r);
					// 设置全局过滤器链： globalFilterChain
					return Mono.just(webHandler);
				})
				// 如果匹配不到，返回 Mono.empty();
				.switchIfEmpty(Mono.empty().then(Mono.fromRunnable(() -> {
					exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
					if (logger.isTraceEnabled()) {
						logger.trace("No RouteDefinition found for ["
								+ getExchangeDesc(exchange) + "]");
					}
				})));
	}

	@Override
	protected CorsConfiguration getCorsConfiguration(Object handler,
			ServerWebExchange exchange) {
		// TODO: support cors configuration via properties on a route see gh-229
		// see RequestMappingHandlerMapping.initCorsConfiguration()
		// also see
		// https://github.com/spring-projects/spring-framework/blob/master/spring-web/src/test/java/org/springframework/web/cors/reactive/CorsWebFilterTests.java
		return super.getCorsConfiguration(handler, exchange);
	}

	// TODO: get desc from factory?
	private String getExchangeDesc(ServerWebExchange exchange) {
		StringBuilder out = new StringBuilder();
		out.append("Exchange: ");
		out.append(exchange.getRequest().getMethod());
		out.append(" ");
		out.append(exchange.getRequest().getURI());
		return out.toString();
	}

	/**
	 * 获取路由
	 * @param exchange
	 * @return
	 */
	protected Mono<Route> lookupRoute(ServerWebExchange exchange) {

		return	// 获得全部 Routes 路由
				this.routeLocator.getRoutes()
				// individually filter routes so that filterWhen error delaying is not a
				// problem
				.concatMap(route -> Mono.just(route)
						// 匹配 Predicate
						.filterWhen(r -> {
					// add the current route we are testing
					exchange.getAttributes().put(GATEWAY_PREDICATE_ROUTE_ATTR, r.getId());
					//  匹配Predicate
					return r.getPredicate().apply(exchange);

				})
						// instead of immediately stopping main flux due to error, log and
						// swallow it
						.doOnError(e -> logger.error(
								"Error applying predicate for route: " + route.getId(),
								e))
						.onErrorResume(e -> Mono.empty()))
				// .defaultIfEmpty() put a static Route not found
				// or .switchIfEmpty()
				// .switchIfEmpty(Mono.<Route>empty().log("noroute"))
				// 只取第一个元素 findFirst(); 每次请求只会对应一个 Route
				.next()
				// TODO: error handling
				.map(route -> {
					if (logger.isDebugEnabled()) {
						logger.debug("Route matched: " + route.getId());
					}
					// 验证路由
					validateRoute(route, exchange);
					return route;
				});

		/*
		 * TODO: trace logging if (logger.isTraceEnabled()) {
		 * logger.trace("RouteDefinition did not match: " + routeDefinition.getId()); }
		 */
	}

	/**
	 * Validate the given handler against the current request.
	 * <p>
	 * The default implementation is empty. Can be overridden in subclasses, for example
	 * to enforce specific preconditions expressed in URL mappings.
	 * @param route the Route object to validate
	 * @param exchange current exchange
	 * @throws Exception if validation failed
	 */
	@SuppressWarnings("UnusedParameters")
	protected void validateRoute(Route route, ServerWebExchange exchange) {
	}

	protected String getSimpleName() {
		return "RoutePredicateHandlerMapping";
	}

	public enum ManagementPortType {

		/**
		 * The management port has been disabled.
		 */
		DISABLED,

		/**
		 * The management port is the same as the server port.
		 */
		SAME,

		/**
		 * The management port and server port are different.
		 */
		DIFFERENT;

	}

}
