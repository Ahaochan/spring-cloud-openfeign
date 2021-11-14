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

package org.springframework.cloud.openfeign;

import feign.Feign;
import feign.Target;
import feign.hystrix.FallbackFactory;
import feign.hystrix.HystrixFeign;
import feign.hystrix.SetterFactory;

import org.springframework.util.StringUtils;

/**
 * @author Spencer Gibb
 * @author Erik Kringen
 */
@SuppressWarnings("unchecked")
// 在FeignAutoConfiguration中, 如果@ConditionalOnClass(name = "feign.hystrix.HystrixFeign")成立, 就会注册HystrixTargeter
// 然后在FeignClientFactoryBean的getTarget方法被注入进去
class HystrixTargeter implements Targeter {

	@Override
	public <T> T target(FeignClientFactoryBean factory, Feign.Builder feign,
			FeignContext context, Target.HardCodedTarget<T> target) {
		// 设置feign.hystrix.enabled为true，FeignClientsConfiguration就会初始化HystrixFeign.Builder对象
		if (!(feign instanceof feign.hystrix.HystrixFeign.Builder)) {
			return feign.target(target);
		}
		feign.hystrix.HystrixFeign.Builder builder = (feign.hystrix.HystrixFeign.Builder) feign;
		String name = StringUtils.isEmpty(factory.getContextId()) ? factory.getName()
				: factory.getContextId();
		SetterFactory setterFactory = getOptional(name, context, SetterFactory.class);
		if (setterFactory != null) {
			builder.setterFactory(setterFactory);
		}
		Class<?> fallback = factory.getFallback();
		if (fallback != void.class) {
			return targetWithFallback(name, context, target, builder, fallback);
		}
		Class<?> fallbackFactory = factory.getFallbackFactory();
		if (fallbackFactory != void.class) {
			return targetWithFallbackFactory(name, context, target, builder,
					fallbackFactory);
		}

		return feign.target(target);
	}

	private <T> T targetWithFallbackFactory(String feignClientName, FeignContext context,
			Target.HardCodedTarget<T> target, HystrixFeign.Builder builder,
			Class<?> fallbackFactoryClass) {
		// 从服务独立的Spring上下文中获取@FeignClient配置的fallbackFactory属性, 创建FallbackFactory
		FallbackFactory<? extends T> fallbackFactory = (FallbackFactory<? extends T>) getFromContext(
				"fallbackFactory", feignClientName, context, fallbackFactoryClass,
				FallbackFactory.class);
		// 调用feign里的HystrixFeign.Builder
		return builder.target(target, fallbackFactory);
	}

	private <T> T targetWithFallback(String feignClientName, FeignContext context,
			Target.HardCodedTarget<T> target, HystrixFeign.Builder builder,
			Class<?> fallback) {
		// 从服务独立的Spring上下文中获取@FeignClient配置的fallback属性, 创建Fallback
		T fallbackInstance = getFromContext("fallback", feignClientName, context,
				fallback, target.type());
		// 调用feign里的HystrixFeign.Builder
		return builder.target(target, fallbackInstance);
	}

	private <T> T getFromContext(String fallbackMechanism, String feignClientName,
			FeignContext context, Class<?> beanType, Class<T> targetType) {
		Object fallbackInstance = context.getInstance(feignClientName, beanType);
		if (fallbackInstance == null) {
			throw new IllegalStateException(String.format(
					"No " + fallbackMechanism
							+ " instance of type %s found for feign client %s",
					beanType, feignClientName));
		}
		// 定义的fallback必须实现@FeignClient修饰的接口
		if (!targetType.isAssignableFrom(beanType)) {
			throw new IllegalStateException(String.format("Incompatible "
					+ fallbackMechanism
					+ " instance. Fallback/fallbackFactory of type %s is not assignable to %s for feign client %s",
					beanType, targetType, feignClientName));
		}
		return (T) fallbackInstance;
	}

	private <T> T getOptional(String feignClientName, FeignContext context,
			Class<T> beanType) {
		return context.getInstance(feignClientName, beanType);
	}

}
