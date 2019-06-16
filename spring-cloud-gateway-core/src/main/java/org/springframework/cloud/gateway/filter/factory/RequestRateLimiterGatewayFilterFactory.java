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

package org.springframework.cloud.gateway.filter.factory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.HttpStatusHolder;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.setResponseStatus;

/**
 * 限流相关
 * User Request Rate Limiter filter. See https://stripe.com/blog/rate-limiters and
 */
@ConfigurationProperties("spring.cloud.gateway.filter.request-rate-limiter")
public class RequestRateLimiterGatewayFilterFactory extends
		AbstractGatewayFilterFactory<RequestRateLimiterGatewayFilterFactory.Config> {

	/**
	 * Key-Resolver key.
	 */
	public static final String KEY_RESOLVER_KEY = "keyResolver";

	private static final String EMPTY_KEY = "____EMPTY_KEY__";

	private final RateLimiter defaultRateLimiter; // 限流器：默认 redisRateLimiter

	private final KeyResolver defaultKeyResolver; // 限流键解析器 PrincipalNameKeyResolver

	/**
	 * Switch to deny requests if the Key Resolver returns an empty key, defaults to true.
	 */
	private boolean denyEmptyKey = true;

	/** HttpStatus to return when denyEmptyKey is true, defaults to FORBIDDEN. */
	private String emptyKeyStatusCode = HttpStatus.FORBIDDEN.name();

	public RequestRateLimiterGatewayFilterFactory(RateLimiter defaultRateLimiter,
			KeyResolver defaultKeyResolver) {
		super(Config.class);
		this.defaultRateLimiter = defaultRateLimiter;
		this.defaultKeyResolver = defaultKeyResolver;
	}

	public KeyResolver getDefaultKeyResolver() {
		return defaultKeyResolver;
	}

	public RateLimiter getDefaultRateLimiter() {
		return defaultRateLimiter;
	}

	public boolean isDenyEmptyKey() {
		return denyEmptyKey;
	}

	public void setDenyEmptyKey(boolean denyEmptyKey) {
		this.denyEmptyKey = denyEmptyKey;
	}

	public String getEmptyKeyStatusCode() {
		return emptyKeyStatusCode;
	}

	public void setEmptyKeyStatusCode(String emptyKeyStatusCode) {
		this.emptyKeyStatusCode = emptyKeyStatusCode;
	}

	@SuppressWarnings("unchecked")
	@Override
	public GatewayFilter apply(Config config) {
		// 获得 限流key解析器
		KeyResolver resolver = getOrDefault(config.keyResolver, defaultKeyResolver);
		// 限流器
		RateLimiter<Object> limiter = getOrDefault(config.rateLimiter,
				defaultRateLimiter);
		// key = null 是否拒绝处理请求
		boolean denyEmpty = getOrDefault(config.denyEmptyKey, this.denyEmptyKey);

		// 状态码
		HttpStatusHolder emptyKeyStatus = HttpStatusHolder
				.parse(getOrDefault(config.emptyKeyStatus, this.emptyKeyStatusCode));

		return (exchange, chain) -> {
			// 路由
			Route route = exchange
					.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);


			return resolver.resolve(exchange).defaultIfEmpty(EMPTY_KEY).flatMap(key -> {
				if (EMPTY_KEY.equals(key)) {
					if (denyEmpty) {
						// key 为空时， 设置空响应码 403
						setResponseStatus(exchange, emptyKeyStatus);
						return exchange.getResponse().setComplete();
					}
					return chain.filter(exchange);
				}
				return
					// 是否限流
					limiter.isAllowed(route.getId(), key)
							//
							.flatMap(response -> {

					// 设置响应头
					for (Map.Entry<String, String> header : response.getHeaders()
							.entrySet()) {
						exchange.getResponse().getHeaders().add(header.getKey(),
								header.getValue());
					}

					// 通过
					if (response.isAllowed()) {
						return chain.filter(exchange);
					}

					// 设置限流响应码
					setResponseStatus(exchange, config.getStatusCode());
					// 请求返回
					return exchange.getResponse().setComplete();
				});
			});
		};
	}

	private <T> T getOrDefault(T configValue, T defaultValue) {
		return (configValue != null) ? configValue : defaultValue;
	}

	public static class Config {

		private KeyResolver keyResolver;

		private RateLimiter rateLimiter;

		private HttpStatus statusCode = HttpStatus.TOO_MANY_REQUESTS;

		private Boolean denyEmptyKey;

		private String emptyKeyStatus;

		public KeyResolver getKeyResolver() {
			return keyResolver;
		}

		public Config setKeyResolver(KeyResolver keyResolver) {
			this.keyResolver = keyResolver;
			return this;
		}

		public RateLimiter getRateLimiter() {
			return rateLimiter;
		}

		public Config setRateLimiter(RateLimiter rateLimiter) {
			this.rateLimiter = rateLimiter;
			return this;
		}

		public HttpStatus getStatusCode() {
			return statusCode;
		}

		public Config setStatusCode(HttpStatus statusCode) {
			this.statusCode = statusCode;
			return this;
		}

		public Boolean getDenyEmptyKey() {
			return denyEmptyKey;
		}

		public Config setDenyEmptyKey(Boolean denyEmptyKey) {
			this.denyEmptyKey = denyEmptyKey;
			return this;
		}

		public String getEmptyKeyStatus() {
			return emptyKeyStatus;
		}

		public Config setEmptyKeyStatus(String emptyKeyStatus) {
			this.emptyKeyStatus = emptyKeyStatus;
			return this;
		}

	}

}
