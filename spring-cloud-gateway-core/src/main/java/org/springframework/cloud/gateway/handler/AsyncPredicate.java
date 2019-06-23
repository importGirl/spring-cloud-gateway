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

import org.reactivestreams.Publisher;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * ##异步## 基于 reactor包 实现都Predicate; 用于条件匹配, 匹配到才能进行路由
 *	使用Tuple 元组：支持不同的数据类型
 * @author Ben Hale
 */
public interface AsyncPredicate<T> extends Function<T, Publisher<Boolean>> {

	default AsyncPredicate<T> and(AsyncPredicate<? super T> other) {
		Assert.notNull(other, "other must not be null");

		// zip:压缩合并 - map:转换
		return t -> Flux.zip(apply(t), other.apply(t))
				// Tuple元组【apply(t),other.apply(t)】 ：apply(t) - tuple.getT1(); other.apply(t) -> tuple.getT2()
				.map(tuple -> tuple.getT1() && tuple.getT2());
	}

	default AsyncPredicate<T> negate() {
		return t -> Mono.from(apply(t)).map(b -> !b);
	}

	default AsyncPredicate<T> or(AsyncPredicate<? super T> other) {
		Assert.notNull(other, "other must not be null");

		return t -> Flux.zip(apply(t), other.apply(t))
				.map(tuple -> tuple.getT1() || tuple.getT2());
	}

}
