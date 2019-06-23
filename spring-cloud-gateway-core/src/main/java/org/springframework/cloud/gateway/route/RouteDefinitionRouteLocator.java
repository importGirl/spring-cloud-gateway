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

package org.springframework.cloud.gateway.route;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.event.FilterArgsEvent;
import org.springframework.cloud.gateway.event.PredicateArgsEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.support.ConfigurationUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.convert.ConversionService;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.validation.Validator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

/**
 * {@link RouteLocator} that loads routes from a {@link RouteDefinitionLocator}.
 *
 * routeLocator 接口核心实现类， 用于将RouteDefinition 转换成 Route
 *
 * @author Spencer Gibb
 */
public class RouteDefinitionRouteLocator
		implements RouteLocator, BeanFactoryAware, ApplicationEventPublisherAware {

	/**
	 * Default filters name.
	 */
	public static final String DEFAULT_FILTERS = "defaultFilters";

	protected final Log logger = LogFactory.getLog(getClass());

	private final RouteDefinitionLocator routeDefinitionLocator;

	private final ConversionService conversionService;

	private final Map<String, RoutePredicateFactory> predicates = new LinkedHashMap<>();// predicate
																						// 工厂列表

	private final Map<String, GatewayFilterFactory> gatewayFilterFactories = new HashMap<>();// GatewayFilterFactory
																								// 工厂列表

	private final GatewayProperties gatewayProperties;// gateway 外部化配置

	private final SpelExpressionParser parser = new SpelExpressionParser(); // Spring EL
																			// 表达式解析器

	private BeanFactory beanFactory;

	private ApplicationEventPublisher publisher;

	@Autowired
	private Validator validator;

	public RouteDefinitionRouteLocator(RouteDefinitionLocator routeDefinitionLocator,
			List<RoutePredicateFactory> predicates,
			List<GatewayFilterFactory> gatewayFilterFactories,
			GatewayProperties gatewayProperties, ConversionService conversionService) {
		this.routeDefinitionLocator = routeDefinitionLocator;
		this.conversionService = conversionService;
		// 初始化 predicates
		initFactories(predicates);
		// 初始化 gatewayFilterFactories
		gatewayFilterFactories.forEach(
				factory -> this.gatewayFilterFactories.put(factory.name(), factory));
		this.gatewayProperties = gatewayProperties;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
		this.publisher = publisher;
	}

	private void initFactories(List<RoutePredicateFactory> predicates) {
		predicates.forEach(factory -> {
			String key = factory.name();
			if (this.predicates.containsKey(key)) {
				this.logger.warn("A RoutePredicateFactory named " + key
						+ " already exists, class: " + this.predicates.get(key)
						+ ". It will be overwritten.");
			}
			this.predicates.put(key, factory);
			if (logger.isInfoEnabled()) {
				logger.info("Loaded RoutePredicateFactory [" + key + "]");
			}
		});
	}

	/**
	 * ////核心方法////
	 * 获得所有路由 Route
	 *
	 * @return
	 */
	@Override
	public Flux<Route> getRoutes() {

		return this.routeDefinitionLocator
				// 获得 路由定义器
				.getRouteDefinitions()
				// 转换 RouteDefinitions -> Route
				.map(this::convertToRoute)
				// TODO: error handling
				// 打印日志
				.map(route -> {
					if (logger.isDebugEnabled()) {
						logger.debug("RouteDefinition matched: " + route.getId());
					}
					return route;
				});

		/*
		 * TODO: trace logging if (logger.isTraceEnabled()) {
		 * logger.trace("RouteDefinition did not match: " + routeDefinition.getId()); }
		 */
	}

	/**
	 * 将 RouteDefinition 转换成 Route
	 * RouteDefinition: Predicate、gatewayFilters
	 *
	 * @param routeDefinition
	 * @return
	 */
	private Route convertToRoute(RouteDefinition routeDefinition) {
		// 合并 Predicates 表达式数组； 得到判断结果 predicate
		AsyncPredicate<ServerWebExchange> predicate = combinePredicates(routeDefinition);
		// 获得 GatewayFilter
		List<GatewayFilter> gatewayFilters = getFilters(routeDefinition);

		// 构建 Route
		return Route.async(routeDefinition).asyncPredicate(predicate)
				.replaceFilters(gatewayFilters).build();
	}

	/**
	 * FilterDefinitions的配置定义 转换成 GatewayFilter
	 * @param id
	 * @param filterDefinitions
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private List<GatewayFilter> loadGatewayFilters(String id,
			List<FilterDefinition> filterDefinitions) {
		// 过滤器集合
		List<GatewayFilter> filters = filterDefinitions.stream().map(definition -> {
			// 获得 GatewayFilterFactory
			GatewayFilterFactory factory = this.gatewayFilterFactories
					.get(definition.getName());
			if (factory == null) {
				throw new IllegalArgumentException(
						"Unable to find GatewayFilterFactory with name "
								+ definition.getName());
			}

			Map<String, String> args = definition.getArgs();
			if (logger.isDebugEnabled()) {
				logger.debug("RouteDefinition " + id + " applying filter " + args + " to "
						+ definition.getName());
			}

			// 解析 参数；${}
			Map<String, Object> properties = factory.shortcutType().normalize(args,
					factory, this.parser, this.beanFactory);

			// 绑定配置对象 Config ; Binder实现
			Object configuration = factory.newConfig();

			ConfigurationUtils.bind(configuration, properties,
					factory.shortcutFieldPrefix(), definition.getName(), validator,
					conversionService);

			// 返回 GatewayFilter
			GatewayFilter gatewayFilter = factory.apply(configuration);
			// 发布过滤器事件
			if (this.publisher != null) {
				this.publisher.publishEvent(new FilterArgsEvent(this, id, properties));
			}
			return gatewayFilter;
		}).collect(Collectors.toList());

		// 添加 GatewayFilter， 转换为 OrderedGatewayFilter
		ArrayList<GatewayFilter> ordered = new ArrayList<>(filters.size());
		for (int i = 0; i < filters.size(); i++) {
			GatewayFilter gatewayFilter = filters.get(i);
			if (gatewayFilter instanceof Ordered) {
				ordered.add(gatewayFilter);
			}
			else {
				ordered.add(new OrderedGatewayFilter(gatewayFilter, i + 1));
			}
		}

		return ordered;
	}

	/**
	 * 获得 GatewayFilter 列表
	 * RouteDefinition -> GatewayFilter 的转换
	 * @param routeDefinition
	 * @return
	 */
	private List<GatewayFilter> getFilters(RouteDefinition routeDefinition) {
		List<GatewayFilter> filters = new ArrayList<>();

		// 添加 默认过滤器
		// TODO: support option to apply defaults after route specific filters?
		if (!this.gatewayProperties.getDefaultFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(DEFAULT_FILTERS,
					this.gatewayProperties.getDefaultFilters()));
		}

		// 添加 配置的过滤器
		if (!routeDefinition.getFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(routeDefinition.getId(),
					routeDefinition.getFilters()));
		}

		// 排序
		AnnotationAwareOrderComparator.sort(filters);
		return filters;
	}

	/**
	 * 合并 AsyncPredicate； 并对合并的AsyncPredicate 表达式数组进行链式判断（and=&&、or=||）
	 * @param routeDefinition
	 * @return
	 */
	private AsyncPredicate<ServerWebExchange> combinePredicates(
			RouteDefinition routeDefinition) {
		// 获得 Predicates 列表； 转换：PredicateDefinition -> Predicate
		List<PredicateDefinition> predicates = routeDefinition.getPredicates();
		AsyncPredicate<ServerWebExchange> predicate = lookup(routeDefinition,
				predicates.get(0));

		// Predicates 表达式数组拼接判断
		for (PredicateDefinition andPredicate : predicates.subList(1, predicates.size())) {
			AsyncPredicate<ServerWebExchange> found = lookup(routeDefinition,
					andPredicate);
			// 执行 and 判断操作（&&）
			predicate = predicate.and(found);
		}

		return predicate;
	}

	/**
	 * 获得 AsyncPredicate: PredicateDefinition定义器 -> AsyncPredicate
	 * @param route
	 * @param predicate
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private AsyncPredicate<ServerWebExchange> lookup(RouteDefinition route,
			PredicateDefinition predicate) {
		// 获得工厂对象
		RoutePredicateFactory<Object> factory = this.predicates.get(predicate.getName());
		if (factory == null) {
			throw new IllegalArgumentException(
					"Unable to find RoutePredicateFactory with name "
							+ predicate.getName());
		}
		// 参数
		Map<String, String> args = predicate.getArgs();
		if (logger.isDebugEnabled()) {
			logger.debug("RouteDefinition " + route.getId() + " applying " + args + " to "
					+ predicate.getName());
		}

		// 解析参数 args; ${} spingEL解析
		Map<String, Object> properties = factory.shortcutType().normalize(args, factory,
				this.parser, this.beanFactory);
		// 创建配置类并绑定
		Object config = factory.newConfig();
		ConfigurationUtils.bind(config, properties, factory.shortcutFieldPrefix(),
				predicate.getName(), validator, conversionService);
		// 发布事件
		if (this.publisher != null) {
			this.publisher.publishEvent(
					new PredicateArgsEvent(this, route.getId(), properties));
		}
		// 生产 AsyncPredicate
		return factory.applyAsync(config);
	}

}
