/*
 * Copyright 2012-2020 the original author or authors.
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

package org.springframework.boot.autoconfigure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * {@link DeferredImportSelector} to handle {@link EnableAutoConfiguration
 * auto-configuration}. This class can also be subclassed if a custom variant of
 * {@link EnableAutoConfiguration @EnableAutoConfiguration} is needed.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Stephane Nicoll
 * @author Madhura Bhave
 * @since 1.3.0
 * @see EnableAutoConfiguration
 */
public class AutoConfigurationImportSelector implements DeferredImportSelector, BeanClassLoaderAware,
		ResourceLoaderAware, BeanFactoryAware, EnvironmentAware, Ordered {

	private static final AutoConfigurationEntry EMPTY_ENTRY = new AutoConfigurationEntry();

	private static final String[] NO_IMPORTS = {};

	private static final Log logger = LogFactory.getLog(AutoConfigurationImportSelector.class);

	private static final String PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE = "spring.autoconfigure.exclude";

	private ConfigurableListableBeanFactory beanFactory;

	private Environment environment;

	private ClassLoader beanClassLoader;

	private ResourceLoader resourceLoader;

	private ConfigurationClassFilter configurationClassFilter;

	/**
	 * 要看这个方法，因为这是实现的方法，而且命名也一眼看到了含义了，就是这里来的
	 * 导入哪些组件就是这里获取的，AnnotationMetadata就是注解的元数据，其实就是启动类
	 * @param annotationMetadata
	 * @return
	 */
	@Override
	public String[] selectImports(AnnotationMetadata annotationMetadata) {
		// 开关配置，没有就直接返回空，不做导入
		if (!isEnabled(annotationMetadata)) {
			return NO_IMPORTS;
		}
		// 通过注解获取要自动装配的内容，所有的核心都落在了这里，获取所有自动装配的类的集合
		AutoConfigurationEntry autoConfigurationEntry = getAutoConfigurationEntry(annotationMetadata);
		return StringUtils.toStringArray(autoConfigurationEntry.getConfigurations());
	}

	@Override
	public Predicate<String> getExclusionFilter() {
		return this::shouldExclude;
	}

	private boolean shouldExclude(String configurationClassName) {
		return getConfigurationClassFilter().filter(Collections.singletonList(configurationClassName)).isEmpty();
	}

	/**
	 * Return the {@link AutoConfigurationEntry} based on the {@link AnnotationMetadata}
	 * of the importing {@link Configuration @Configuration} class.
	 * @param annotationMetadata the annotation metadata of the configuration class
	 * @return the auto-configurations that should be imported
	 */
	protected AutoConfigurationEntry getAutoConfigurationEntry(AnnotationMetadata annotationMetadata) {
		if (!isEnabled(annotationMetadata)) {
			return EMPTY_ENTRY;
		}
		AnnotationAttributes attributes = getAttributes(annotationMetadata);
		// 获取所有的候选的需要自动装配的组件，所以这里就是获取自动装配的地方了，自动装配的重点核心
		List<String> configurations = getCandidateConfigurations(annotationMetadata, attributes);
		// 做去重
		configurations = removeDuplicates(configurations);
		// 做这个注解上排除的组件，EnableAutoConfiguration注解上有一个exclude属性，可以配置要排除的组件，这里就是拿到需要排除的
		Set<String> exclusions = getExclusions(annotationMetadata, attributes);
		// 校验你要排除的类是不是存在之类的一些东西，存在的才排除，不存在的没啥意义过滤掉，也不报错
		checkExcludedClasses(configurations, exclusions);
		// 过滤掉不需要的，就是过滤掉exclude属性上配置的组件
		configurations.removeAll(exclusions);
		// 因为是取得所有的factoris文件的，我们只要自动装配的，所以这里要做一次过滤，而且这里只过滤我们导入的，没导入的直接过滤掉
		configurations = getConfigurationClassFilter().filter(configurations);
		fireAutoConfigurationImportEvents(configurations, exclusions);
		return new AutoConfigurationEntry(configurations, exclusions);
	}

	@Override
	public Class<? extends Group> getImportGroup() {
		return AutoConfigurationGroup.class;
	}

	protected boolean isEnabled(AnnotationMetadata metadata) {
		if (getClass() == AutoConfigurationImportSelector.class) {
			return getEnvironment().getProperty(EnableAutoConfiguration.ENABLED_OVERRIDE_PROPERTY, Boolean.class, true);
		}
		return true;
	}

	/**
	 * Return the appropriate {@link AnnotationAttributes} from the
	 * {@link AnnotationMetadata}. By default this method will return attributes for
	 * {@link #getAnnotationClass()}.
	 * @param metadata the annotation metadata
	 * @return annotation attributes
	 */
	protected AnnotationAttributes getAttributes(AnnotationMetadata metadata) {
		String name = getAnnotationClass().getName();
		AnnotationAttributes attributes = AnnotationAttributes.fromMap(metadata.getAnnotationAttributes(name, true));
		Assert.notNull(attributes, () -> "No auto-configuration attributes found. Is " + metadata.getClassName()
				+ " annotated with " + ClassUtils.getShortName(name) + "?");
		return attributes;
	}

	/**
	 * Return the source annotation class used by the selector.
	 * @return the annotation class
	 */
	protected Class<?> getAnnotationClass() {
		return EnableAutoConfiguration.class;
	}

	/**
	 * Return the auto-configuration class names that should be considered. By default
	 * this method will load candidates using {@link SpringFactoriesLoader} with
	 * {@link #getSpringFactoriesLoaderFactoryClass()}.
	 * @param metadata the source metadata
	 * @param attributes the {@link #getAttributes(AnnotationMetadata) annotation
	 * attributes}
	 * @return a list of candidate configurations
	 * 获取需要自动装配的组件
	 */
	protected List<String> getCandidateConfigurations(AnnotationMetadata metadata, AnnotationAttributes attributes) {
		/**
		 * 加载文件通过SpringFactoriesLoader的能力，就是读取文件
		 * 我们来解读一下这个方法，这个方法传入两个参数，第一个是EnableAutoConfiguration这个类的名字，第二个是类加载器
		 * 其底层实现就是从我们当先系统，注意是系统，加载所有的"META-INF/spring.factories"文件，也就是读取所有的META-INF目录下的spring.factories文件
		 * 读取文件中的内容，然后返回一个List集合，这个List集合就是我们需要的自动装配的组件，也就是EnableAutoConfiguration注解上配置的类
		 * 这就带来一个问题，其实当前项目很多jar下面都有这个目录文件，他都会拿到，那么他都要吗，其实不是的，他会通过组件来过滤最后取到
		 * org.springframework.boot.autoconfigure.EnableAutoConfiguration.class这个类的全限定名，然后通过SpringFactoriesLoader去过滤
		 *
		 */
		List<String> configurations = SpringFactoriesLoader.loadFactoryNames(getSpringFactoriesLoaderFactoryClass(),
				getBeanClassLoader());
		Assert.notEmpty(configurations, "No auto configuration classes found in META-INF/spring.factories. If you "
				+ "are using a custom packaging, make sure that file is correct.");
		return configurations;
	}

	/**
	 * Return the class used by {@link SpringFactoriesLoader} to load configuration
	 * candidates.
	 * @return the factory class
	 */
	protected Class<?> getSpringFactoriesLoaderFactoryClass() {
		return EnableAutoConfiguration.class;
	}

	private void checkExcludedClasses(List<String> configurations, Set<String> exclusions) {
		List<String> invalidExcludes = new ArrayList<>(exclusions.size());
		for (String exclusion : exclusions) {
			if (ClassUtils.isPresent(exclusion, getClass().getClassLoader()) && !configurations.contains(exclusion)) {
				invalidExcludes.add(exclusion);
			}
		}
		if (!invalidExcludes.isEmpty()) {
			handleInvalidExcludes(invalidExcludes);
		}
	}

	/**
	 * Handle any invalid excludes that have been specified.
	 * @param invalidExcludes the list of invalid excludes (will always have at least one
	 * element)
	 */
	protected void handleInvalidExcludes(List<String> invalidExcludes) {
		StringBuilder message = new StringBuilder();
		for (String exclude : invalidExcludes) {
			message.append("\t- ").append(exclude).append(String.format("%n"));
		}
		throw new IllegalStateException(String.format(
				"The following classes could not be excluded because they are not auto-configuration classes:%n%s",
				message));
	}

	/**
	 * Return any exclusions that limit the candidate configurations.
	 * @param metadata the source metadata
	 * @param attributes the {@link #getAttributes(AnnotationMetadata) annotation
	 * attributes}
	 * @return exclusions or an empty set
	 */
	protected Set<String> getExclusions(AnnotationMetadata metadata, AnnotationAttributes attributes) {
		Set<String> excluded = new LinkedHashSet<>();
		excluded.addAll(asList(attributes, "exclude"));
		excluded.addAll(Arrays.asList(attributes.getStringArray("excludeName")));
		excluded.addAll(getExcludeAutoConfigurationsProperty());
		return excluded;
	}

	/**
	 * Returns the auto-configurations excluded by the
	 * {@code spring.autoconfigure.exclude} property.
	 * @return excluded auto-configurations
	 * @since 2.3.2
	 */
	protected List<String> getExcludeAutoConfigurationsProperty() {
		Environment environment = getEnvironment();
		if (environment == null) {
			return Collections.emptyList();
		}
		if (environment instanceof ConfigurableEnvironment) {
			Binder binder = Binder.get(environment);
			return binder.bind(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class).map(Arrays::asList)
					.orElse(Collections.emptyList());
		}
		String[] excludes = environment.getProperty(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class);
		return (excludes != null) ? Arrays.asList(excludes) : Collections.emptyList();
	}

	protected List<AutoConfigurationImportFilter> getAutoConfigurationImportFilters() {
		return SpringFactoriesLoader.loadFactories(AutoConfigurationImportFilter.class, this.beanClassLoader);
	}

	private ConfigurationClassFilter getConfigurationClassFilter() {
		if (this.configurationClassFilter == null) {
			List<AutoConfigurationImportFilter> filters = getAutoConfigurationImportFilters();
			for (AutoConfigurationImportFilter filter : filters) {
				invokeAwareMethods(filter);
			}
			this.configurationClassFilter = new ConfigurationClassFilter(this.beanClassLoader, filters);
		}
		return this.configurationClassFilter;
	}

	protected final <T> List<T> removeDuplicates(List<T> list) {
		return new ArrayList<>(new LinkedHashSet<>(list));
	}

	protected final List<String> asList(AnnotationAttributes attributes, String name) {
		String[] value = attributes.getStringArray(name);
		return Arrays.asList(value);
	}

	private void fireAutoConfigurationImportEvents(List<String> configurations, Set<String> exclusions) {
		List<AutoConfigurationImportListener> listeners = getAutoConfigurationImportListeners();
		if (!listeners.isEmpty()) {
			AutoConfigurationImportEvent event = new AutoConfigurationImportEvent(this, configurations, exclusions);
			for (AutoConfigurationImportListener listener : listeners) {
				invokeAwareMethods(listener);
				listener.onAutoConfigurationImportEvent(event);
			}
		}
	}

	protected List<AutoConfigurationImportListener> getAutoConfigurationImportListeners() {
		return SpringFactoriesLoader.loadFactories(AutoConfigurationImportListener.class, this.beanClassLoader);
	}

	private void invokeAwareMethods(Object instance) {
		if (instance instanceof Aware) {
			if (instance instanceof BeanClassLoaderAware) {
				((BeanClassLoaderAware) instance).setBeanClassLoader(this.beanClassLoader);
			}
			if (instance instanceof BeanFactoryAware) {
				((BeanFactoryAware) instance).setBeanFactory(this.beanFactory);
			}
			if (instance instanceof EnvironmentAware) {
				((EnvironmentAware) instance).setEnvironment(this.environment);
			}
			if (instance instanceof ResourceLoaderAware) {
				((ResourceLoaderAware) instance).setResourceLoader(this.resourceLoader);
			}
		}
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		Assert.isInstanceOf(ConfigurableListableBeanFactory.class, beanFactory);
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}

	protected final ConfigurableListableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.beanClassLoader = classLoader;
	}

	protected ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	protected final Environment getEnvironment() {
		return this.environment;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	protected final ResourceLoader getResourceLoader() {
		return this.resourceLoader;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

	private static class ConfigurationClassFilter {

		private final AutoConfigurationMetadata autoConfigurationMetadata;

		private final List<AutoConfigurationImportFilter> filters;

		ConfigurationClassFilter(ClassLoader classLoader, List<AutoConfigurationImportFilter> filters) {
			this.autoConfigurationMetadata = AutoConfigurationMetadataLoader.loadMetadata(classLoader);
			this.filters = filters;
		}

		List<String> filter(List<String> configurations) {
			long startTime = System.nanoTime();
			String[] candidates = StringUtils.toStringArray(configurations);
			boolean skipped = false;
			for (AutoConfigurationImportFilter filter : this.filters) {
				boolean[] match = filter.match(candidates, this.autoConfigurationMetadata);
				for (int i = 0; i < match.length; i++) {
					if (!match[i]) {
						candidates[i] = null;
						skipped = true;
					}
				}
			}
			if (!skipped) {
				return configurations;
			}
			List<String> result = new ArrayList<>(candidates.length);
			for (String candidate : candidates) {
				if (candidate != null) {
					result.add(candidate);
				}
			}
			if (logger.isTraceEnabled()) {
				int numberFiltered = configurations.size() - result.size();
				logger.trace("Filtered " + numberFiltered + " auto configuration class in "
						+ TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime) + " ms");
			}
			return result;
		}

	}

	private static class AutoConfigurationGroup
			implements DeferredImportSelector.Group, BeanClassLoaderAware, BeanFactoryAware, ResourceLoaderAware {

		private final Map<String, AnnotationMetadata> entries = new LinkedHashMap<>();

		private final List<AutoConfigurationEntry> autoConfigurationEntries = new ArrayList<>();

		private ClassLoader beanClassLoader;

		private BeanFactory beanFactory;

		private ResourceLoader resourceLoader;

		private AutoConfigurationMetadata autoConfigurationMetadata;

		@Override
		public void setBeanClassLoader(ClassLoader classLoader) {
			this.beanClassLoader = classLoader;
		}

		@Override
		public void setBeanFactory(BeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		@Override
		public void setResourceLoader(ResourceLoader resourceLoader) {
			this.resourceLoader = resourceLoader;
		}

		@Override
		public void process(AnnotationMetadata annotationMetadata, DeferredImportSelector deferredImportSelector) {
			Assert.state(deferredImportSelector instanceof AutoConfigurationImportSelector,
					() -> String.format("Only %s implementations are supported, got %s",
							AutoConfigurationImportSelector.class.getSimpleName(),
							deferredImportSelector.getClass().getName()));
			AutoConfigurationEntry autoConfigurationEntry = ((AutoConfigurationImportSelector) deferredImportSelector)
					.getAutoConfigurationEntry(annotationMetadata);
			this.autoConfigurationEntries.add(autoConfigurationEntry);
			for (String importClassName : autoConfigurationEntry.getConfigurations()) {
				this.entries.putIfAbsent(importClassName, annotationMetadata);
			}
		}

		@Override
		public Iterable<Entry> selectImports() {
			if (this.autoConfigurationEntries.isEmpty()) {
				return Collections.emptyList();
			}
			Set<String> allExclusions = this.autoConfigurationEntries.stream()
					.map(AutoConfigurationEntry::getExclusions).flatMap(Collection::stream).collect(Collectors.toSet());
			Set<String> processedConfigurations = this.autoConfigurationEntries.stream()
					.map(AutoConfigurationEntry::getConfigurations).flatMap(Collection::stream)
					.collect(Collectors.toCollection(LinkedHashSet::new));
			processedConfigurations.removeAll(allExclusions);

			return sortAutoConfigurations(processedConfigurations, getAutoConfigurationMetadata()).stream()
					.map((importClassName) -> new Entry(this.entries.get(importClassName), importClassName))
					.collect(Collectors.toList());
		}

		private AutoConfigurationMetadata getAutoConfigurationMetadata() {
			if (this.autoConfigurationMetadata == null) {
				this.autoConfigurationMetadata = AutoConfigurationMetadataLoader.loadMetadata(this.beanClassLoader);
			}
			return this.autoConfigurationMetadata;
		}

		private List<String> sortAutoConfigurations(Set<String> configurations,
				AutoConfigurationMetadata autoConfigurationMetadata) {
			return new AutoConfigurationSorter(getMetadataReaderFactory(), autoConfigurationMetadata)
					.getInPriorityOrder(configurations);
		}

		private MetadataReaderFactory getMetadataReaderFactory() {
			try {
				return this.beanFactory.getBean(SharedMetadataReaderFactoryContextInitializer.BEAN_NAME,
						MetadataReaderFactory.class);
			}
			catch (NoSuchBeanDefinitionException ex) {
				return new CachingMetadataReaderFactory(this.resourceLoader);
			}
		}

	}

	protected static class AutoConfigurationEntry {

		private final List<String> configurations;

		private final Set<String> exclusions;

		private AutoConfigurationEntry() {
			this.configurations = Collections.emptyList();
			this.exclusions = Collections.emptySet();
		}

		/**
		 * Create an entry with the configurations that were contributed and their
		 * exclusions.
		 * @param configurations the configurations that should be imported
		 * @param exclusions the exclusions that were applied to the original list
		 */
		AutoConfigurationEntry(Collection<String> configurations, Collection<String> exclusions) {
			this.configurations = new ArrayList<>(configurations);
			this.exclusions = new HashSet<>(exclusions);
		}

		public List<String> getConfigurations() {
			return this.configurations;
		}

		public Set<String> getExclusions() {
			return this.exclusions;
		}

	}

}
