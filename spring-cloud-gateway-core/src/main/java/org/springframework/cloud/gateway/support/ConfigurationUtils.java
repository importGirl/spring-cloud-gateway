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

package org.springframework.cloud.gateway.support;

import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.convert.ConversionService;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Validator;

import java.util.Collections;
import java.util.Map;

public abstract class ConfigurationUtils {

	public static void bind(Object o, Map<String, Object> properties,
			String configurationPropertyName, String bindingName, Validator validator) {
		bind(o, properties, configurationPropertyName, bindingName, validator, null);
	}

	/**
	 * 绑定properties 中的属性到 对象 o上
	 * @param o								config对象
	 * @param properties					配置属性集合
	 * @param configurationPropertyName		配置名称 ：""
	 * @param bindingName
	 * @param validator
	 * @param conversionService
	 */
	public static void bind(Object o, Map<String, Object> properties,
			String configurationPropertyName, String bindingName, Validator validator,
			ConversionService conversionService) {
		// 找到对象
		Object toBind = getTargetObject(o);

		// 绑定属性
		new Binder(
				Collections.singletonList(new MapConfigurationPropertySource(properties)),
				null, conversionService).bind(configurationPropertyName,
						Bindable.ofInstance(toBind));

		if (validator != null) {
			BindingResult errors = new BeanPropertyBindingResult(toBind, bindingName);
			validator.validate(toBind, errors);
			if (errors.hasErrors()) {
				throw new RuntimeException(new BindException(errors));
			}
		}
	}

	@SuppressWarnings("unchecked")
	public static <T> T getTargetObject(Object candidate) {
		try {
			if (AopUtils.isAopProxy(candidate) && (candidate instanceof Advised)) {
				return (T) ((Advised) candidate).getTargetSource().getTarget();
			}
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to unwrap proxied object", ex);
		}
		return (T) candidate;
	}

}
