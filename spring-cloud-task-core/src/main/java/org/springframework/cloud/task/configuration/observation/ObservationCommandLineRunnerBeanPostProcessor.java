/*
 * Copyright 2013-present the original author or authors.
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

package org.springframework.cloud.task.configuration.observation;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.CommandLineRunner;

/**
 * Registers beans related to task scheduling.
 *
 * @author Marcin Grzejszczak
 */
class ObservationCommandLineRunnerBeanPostProcessor implements BeanPostProcessor {

	private final BeanFactory beanFactory;

	ObservationCommandLineRunnerBeanPostProcessor(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (bean instanceof CommandLineRunner commandLineRunner && !(bean instanceof ObservationCommandLineRunner)) {
			return new ObservationCommandLineRunner(this.beanFactory, commandLineRunner, beanName);
		}
		return bean;
	}

}
