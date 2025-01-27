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

package org.springframework.cloud.gateway.filter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestBodySpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

import static org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter.filterRequest;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;

/**
 * http://、 https:// 路由网关过滤器
 * @author Spencer Gibb
 */
public class WebClientHttpRoutingFilter implements GlobalFilter, Ordered {

	private final WebClient webClient;

	private final ObjectProvider<List<HttpHeadersFilter>> headersFiltersProvider;

	// do not use this headersFilters directly, use getHeadersFilters() instead.
	private volatile List<HttpHeadersFilter> headersFilters;

	public WebClientHttpRoutingFilter(WebClient webClient,
			ObjectProvider<List<HttpHeadersFilter>> headersFiltersProvider) {
		this.webClient = webClient;
		this.headersFiltersProvider = headersFiltersProvider;
	}

	public List<HttpHeadersFilter> getHeadersFilters() {
		if (headersFilters == null) {
			headersFilters = headersFiltersProvider.getIfAvailable();
		}
		return headersFilters;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		// 请求url
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);

		// path协议
		String scheme = requestUrl.getScheme();
		// 是否已经过滤 || http|| https
		if (isAlreadyRouted(exchange)
				|| (!"http".equals(scheme) && !"https".equals(scheme))) {
			return chain.filter(exchange);
		}
		// 设置已经过滤
		setAlreadyRouted(exchange);

		// 请求
		ServerHttpRequest request = exchange.getRequest();

		// mthod
		HttpMethod method = request.getMethod();

		// 处理请求头
		HttpHeaders filteredHeaders = filterRequest(getHeadersFilters(), exchange);

		//是否保存 header:host
		boolean preserveHost = exchange
				.getAttributeOrDefault(PRESERVE_HOST_HEADER_ATTRIBUTE, false);

		// 构建请求体
		RequestBodySpec bodySpec = this.webClient.method(method).uri(requestUrl)
				.headers(httpHeaders -> {
					httpHeaders.addAll(filteredHeaders);
					// TODO: can this support preserviceHostHeader?
					if (!preserveHost) {
						httpHeaders.remove(HttpHeaders.HOST);
					}
				});

		RequestHeadersSpec<?> headersSpec;

		if (requiresBody(method)) {
			headersSpec = bodySpec.body(BodyInserters.fromDataBuffers(request.getBody()));
		}
		else {
			headersSpec = bodySpec;
		}

		// 执行请求
		return headersSpec.exchange()
				// .log("webClient route")
				// 处理后端响应
				.flatMap(res -> {
					ServerHttpResponse response = exchange.getResponse();
					response.getHeaders().putAll(res.headers().asHttpHeaders());
					response.setStatusCode(res.statusCode());
					// Defer committing the response until all route filters have run
					// Put client response as ServerWebExchange attribute and write
					// response later NettyWriteResponseFilter
					exchange.getAttributes().put(CLIENT_RESPONSE_ATTR, res);
					return chain.filter(exchange);
				});
	}

	private boolean requiresBody(HttpMethod method) {
		switch (method) {
		case PUT:
		case POST:
		case PATCH:
			return true;
		default:
			return false;
		}
	}

}
