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

package org.springframework.cloud.gateway.discovery;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.core.style.ToStringCreator;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.Map;
import java.util.function.Predicate;

/**
 * TODO: change to RouteLocator? use java dsl 调用 DiscoveryClient 获取注册在注册中心的服务列表
 *
 * @author Spencer Gibb
 */
public class DiscoveryClientRouteDefinitionLocator implements RouteDefinitionLocator {

	private static final Log log = LogFactory
			.getLog(DiscoveryClientRouteDefinitionLocator.class);

	private final DiscoveryClient discoveryClient;

	private final DiscoveryLocatorProperties properties;

	private final String routeIdPrefix; // 路由编号前缀 discoveryClient类名 + _

	private final SimpleEvaluationContext evalCtxt;

	public DiscoveryClientRouteDefinitionLocator(DiscoveryClient discoveryClient,
			DiscoveryLocatorProperties properties) {
		this.discoveryClient = discoveryClient;
		this.properties = properties;
		if (StringUtils.hasText(properties.getRouteIdPrefix())) {
			this.routeIdPrefix = properties.getRouteIdPrefix();
		}
		else {
			this.routeIdPrefix = this.discoveryClient.getClass().getSimpleName() + "_";
		}
		evalCtxt = SimpleEvaluationContext.forReadOnlyDataBinding().withInstanceMethods()
				.build();
	}

	@Override
	public Flux<RouteDefinition> getRouteDefinitions() {

		SpelExpressionParser parser = new SpelExpressionParser();
		Expression includeExpr = parser
				.parseExpression(properties.getIncludeExpression());
		Expression urlExpr = parser.parseExpression(properties.getUrlExpression());

		Predicate<ServiceInstance> includePredicate;
		if (properties.getIncludeExpression() == null
				|| "true".equalsIgnoreCase(properties.getIncludeExpression())) {
			includePredicate = instance -> true;
		}
		else {
			includePredicate = instance -> {
				Boolean include = includeExpr.getValue(evalCtxt, instance, Boolean.class);
				if (include == null) {
					return false;
				}
				return include;
			};
		}

		return Flux.fromIterable(discoveryClient.getServices()) // 获取服务列表
				.map(discoveryClient::getInstances) // 获取服务对象
				.filter(instances -> !instances.isEmpty())
				.map(instances -> instances.get(0)).filter(includePredicate)
				.map(instance -> {
					String serviceId = instance.getServiceId();

					RouteDefinition routeDefinition = new RouteDefinition();
					// 设置id
					routeDefinition.setId(this.routeIdPrefix + serviceId);
					String uri = urlExpr.getValue(evalCtxt, instance, String.class);
					// 设置uri 格式：lb://${serviceId}
					routeDefinition.setUri(URI.create(uri));

					// ServiceInstance 装饰对象
					final ServiceInstance instanceForEval = new DelegatingServiceInstance(
							instance, properties);

					// 设置 Predicates
					for (PredicateDefinition original : this.properties.getPredicates()) {
						PredicateDefinition predicate = new PredicateDefinition();
						predicate.setName(original.getName());
						for (Map.Entry<String, String> entry : original.getArgs()
								.entrySet()) {
							String value = getValueFromExpr(evalCtxt, parser,
									instanceForEval, entry);
							predicate.addArg(entry.getKey(), value);
						}
						routeDefinition.getPredicates().add(predicate);
					}

					// 设置 Filters
					for (FilterDefinition original : this.properties.getFilters()) {
						FilterDefinition filter = new FilterDefinition();
						filter.setName(original.getName());
						for (Map.Entry<String, String> entry : original.getArgs()
								.entrySet()) {
							String value = getValueFromExpr(evalCtxt, parser,
									instanceForEval, entry);
							filter.addArg(entry.getKey(), value);
						}
						routeDefinition.getFilters().add(filter);
					}

					return routeDefinition;
				});
	}

	String getValueFromExpr(SimpleEvaluationContext evalCtxt, SpelExpressionParser parser,
			ServiceInstance instance, Map.Entry<String, String> entry) {
		try {
			Expression valueExpr = parser.parseExpression(entry.getValue());
			return valueExpr.getValue(evalCtxt, instance, String.class);
		}
		catch (ParseException | EvaluationException e) {
			if (log.isDebugEnabled()) {
				log.debug("Unable to parse " + entry.getValue(), e);
			}
			throw e;
		}
	}

	/** 装饰模式, 增强getServiceId方法 */
	private static class DelegatingServiceInstance implements ServiceInstance {

		final ServiceInstance delegate;

		private final DiscoveryLocatorProperties properties;

		private DelegatingServiceInstance(ServiceInstance delegate,
				DiscoveryLocatorProperties properties) {
			this.delegate = delegate;
			this.properties = properties;
		}

		@Override
		public String getServiceId() {
			if (properties.isLowerCaseServiceId()) {
				return delegate.getServiceId().toLowerCase();
			}
			return delegate.getServiceId();
		}

		@Override
		public String getHost() {
			return delegate.getHost();
		}

		@Override
		public int getPort() {
			return delegate.getPort();
		}

		@Override
		public boolean isSecure() {
			return delegate.isSecure();
		}

		@Override
		public URI getUri() {
			return delegate.getUri();
		}

		@Override
		public Map<String, String> getMetadata() {
			return delegate.getMetadata();
		}

		@Override
		public String getScheme() {
			return delegate.getScheme();
		}

		@Override
		public String toString() {
			return new ToStringCreator(this).append("delegate", delegate)
					.append("properties", properties).toString();
		}

	}

}
