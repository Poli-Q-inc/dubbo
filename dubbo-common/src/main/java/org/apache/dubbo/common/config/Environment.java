/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.config;

import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.context.FrameworkExt;
import org.apache.dubbo.common.context.LifecycleAdapter;
import org.apache.dubbo.common.extension.DisableInject;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.config.AbstractConfig;
import org.apache.dubbo.config.context.ConfigConfigurationAdapter;
import org.apache.dubbo.rpc.model.ApplicationModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class Environment extends LifecycleAdapter implements FrameworkExt {
    private static final Logger logger = LoggerFactory.getLogger(Environment.class);

    public static final String NAME = "environment";

    // dubbo properties in classpath
    private PropertiesConfiguration propertiesConfiguration;

    // java system props (-D)
    private SystemConfiguration systemConfiguration;

    // java system environment
    private EnvironmentConfiguration environmentConfiguration;

    // external config, such as config-center global/default config
    private InmemoryConfiguration externalConfiguration;

    // external app config, such as config-center app config
    private InmemoryConfiguration appExternalConfiguration;

    // local app config , such as Spring Environment/PropertySources/application.properties
    private InmemoryConfiguration appConfiguration;

    private CompositeConfiguration globalConfiguration;
    private CompositeConfiguration dynamicGlobalConfiguration;

    private DynamicConfiguration dynamicConfiguration;

    private List<Map<String, String>> globalConfigurationMaps;

    private String localMigrationRule;

    private AtomicBoolean initialized = new AtomicBoolean(false);
    private ApplicationModel applicationModel;

    public Environment(ApplicationModel applicationModel) {
        this.applicationModel = applicationModel;
    }

    @Override
    public void initialize() throws IllegalStateException {
        if (initialized.compareAndSet(false, true)) {
            this.propertiesConfiguration = new PropertiesConfiguration(applicationModel);
            this.systemConfiguration = new SystemConfiguration();
            this.environmentConfiguration = new EnvironmentConfiguration();
            this.externalConfiguration = new InmemoryConfiguration("ExternalConfig");
            this.appExternalConfiguration = new InmemoryConfiguration("AppExternalConfig");
            this.appConfiguration = new InmemoryConfiguration("AppConfig");

            loadMigrationRule();
        }
    }

    private void loadMigrationRule() {
        String path = System.getProperty(CommonConstants.DUBBO_MIGRATION_KEY);
        if (path == null || path.length() == 0) {
            path = System.getenv(CommonConstants.DUBBO_MIGRATION_KEY);
            if (path == null || path.length() == 0) {
                path = CommonConstants.DEFAULT_DUBBO_MIGRATION_FILE;
            }
        }
        this.localMigrationRule = ConfigUtils.loadMigrationRule(applicationModel.getClassLoaders(), path);
    }

    @Deprecated
    public void setLocalMigrationRule(String localMigrationRule) {
        this.localMigrationRule = localMigrationRule;
    }

    @DisableInject
    public void setExternalConfigMap(Map<String, String> externalConfiguration) {
        if (externalConfiguration != null) {
            this.externalConfiguration.setProperties(externalConfiguration);
        }
    }

    @DisableInject
    public void setAppExternalConfigMap(Map<String, String> appExternalConfiguration) {
        if (appExternalConfiguration != null) {
            this.appExternalConfiguration.setProperties(appExternalConfiguration);
        }
    }

    @DisableInject
    public void setAppConfigMap(Map<String, String> appConfiguration) {
        if (appConfiguration != null) {
            this.appConfiguration.setProperties(appConfiguration);
        }
    }

    public Map<String, String> getExternalConfigMap() {
        return externalConfiguration.getProperties();
    }

    public Map<String, String> getAppExternalConfigMap() {
        return appExternalConfiguration.getProperties();
    }

    public Map<String, String> getAppConfigMap() {
        return appConfiguration.getProperties();
    }

    public void updateExternalConfigMap(Map<String, String> externalMap) {
        this.externalConfiguration.addProperties(externalMap);
    }

    public void updateAppExternalConfigMap(Map<String, String> externalMap) {
        this.appExternalConfiguration.addProperties(externalMap);
    }

    /**
     * Merge target map properties into app configuration
     * @param map
     */
    public void updateAppConfigMap(Map<String, String> map) {
        this.appConfiguration.addProperties(map);
    }

    /**
     * At start-up, Dubbo is driven by various configuration, such as Application, Registry, Protocol, etc.
     * All configurations will be converged into a data bus - URL, and then drive the subsequent process.
     * <p>
     * At present, there are many configuration sources, including AbstractConfig (API, XML, annotation), - D, config center, etc.
     * This method helps us to filter out the most priority values from various configuration sources.
     *
     * @param config
     * @param prefix
     * @return
     */
    public Configuration getPrefixedConfiguration(AbstractConfig config, String prefix) {

        // The sequence would be: SystemConfiguration -> AppExternalConfiguration -> ExternalConfiguration  -> AppConfiguration -> AbstractConfig -> PropertiesConfiguration
        Configuration instanceConfiguration = new ConfigConfigurationAdapter(config, prefix);
        CompositeConfiguration compositeConfiguration = new CompositeConfiguration();
        compositeConfiguration.addConfiguration(systemConfiguration);
        compositeConfiguration.addConfiguration(environmentConfiguration);
        compositeConfiguration.addConfiguration(appExternalConfiguration);
        compositeConfiguration.addConfiguration(externalConfiguration);
        compositeConfiguration.addConfiguration(appConfiguration);
        compositeConfiguration.addConfiguration(instanceConfiguration);
        compositeConfiguration.addConfiguration(propertiesConfiguration);

        return new PrefixedConfiguration(compositeConfiguration, prefix);
    }

    /**
     * There are two ways to get configuration during exposure / reference or at runtime:
     * 1. URL, The value in the URL is relatively fixed. we can get value directly.
     * 2. The configuration exposed in this method is convenient for us to query the latest values from multiple
     * prioritized sources, it also guarantees that configs changed dynamically can take effect on the fly.
     */
    public CompositeConfiguration getConfiguration() {
        if (globalConfiguration == null) {
            CompositeConfiguration configuration = new CompositeConfiguration();
            configuration.addConfiguration(systemConfiguration);
            configuration.addConfiguration(environmentConfiguration);
            configuration.addConfiguration(appExternalConfiguration);
            configuration.addConfiguration(externalConfiguration);
            configuration.addConfiguration(appConfiguration);
            configuration.addConfiguration(propertiesConfiguration);
            globalConfiguration = configuration;
        }
        return globalConfiguration;
    }

    /**
     * Get configuration map list for target instance
     * @param config
     * @param prefix
     * @return
     */
    public List<Map<String, String>> getConfigurationMaps(AbstractConfig config, String prefix) {
        // The sequence would be: SystemConfiguration -> AppExternalConfiguration -> ExternalConfiguration  -> AppConfiguration -> AbstractConfig -> PropertiesConfiguration

        List<Map<String, String>> maps = new ArrayList<>();
        maps.add(systemConfiguration.getProperties());
        maps.add(environmentConfiguration.getProperties());
        maps.add(appExternalConfiguration.getProperties());
        maps.add(externalConfiguration.getProperties());
        maps.add(appConfiguration.getProperties());
        if (config != null) {
            ConfigConfigurationAdapter configurationAdapter = new ConfigConfigurationAdapter(config, prefix);
            maps.add(configurationAdapter.getProperties());
        }
        maps.add(propertiesConfiguration.getProperties());
        return maps;
    }

    /**
     * Get global configuration as map list
     * @return
     */
    public List<Map<String, String>> getConfigurationMaps() {
        if (globalConfigurationMaps == null) {
            globalConfigurationMaps = getConfigurationMaps(null, null);
        }
        return globalConfigurationMaps;
    }

    public Configuration getDynamicGlobalConfiguration() {
        if (dynamicGlobalConfiguration == null) {
            if (dynamicConfiguration == null) {
                if (logger.isWarnEnabled()) {
                    logger.warn("dynamicConfiguration is null , return globalConfiguration.");
                }
                return getConfiguration();
            }
            dynamicGlobalConfiguration = new CompositeConfiguration();
            dynamicGlobalConfiguration.addConfiguration(dynamicConfiguration);
            dynamicGlobalConfiguration.addConfiguration(getConfiguration());
        }
        return dynamicGlobalConfiguration;
    }

    public Optional<DynamicConfiguration> getDynamicConfiguration() {
        return Optional.ofNullable(dynamicConfiguration);
    }

    @DisableInject
    public void setDynamicConfiguration(DynamicConfiguration dynamicConfiguration) {
        this.dynamicConfiguration = dynamicConfiguration;
    }

    @Override
    public void destroy() throws IllegalStateException {
        initialized.set(false);
        systemConfiguration = null;
        propertiesConfiguration = null;
        environmentConfiguration = null;
        externalConfiguration = null;
        appExternalConfiguration = null;
        appConfiguration = null;
        globalConfiguration = null;
        globalConfigurationMaps = null;
        dynamicConfiguration = null;
        dynamicGlobalConfiguration = null;
    }

    /**
     * Reset environment.
     * For test only.
     */
    public void reset() {
        destroy();
        initialize();
    }

    public String resolvePlaceholders(String str) {
        return ConfigUtils.replaceProperty(str, getConfiguration());
    }

    public PropertiesConfiguration getPropertiesConfiguration() {
        return propertiesConfiguration;
    }

    public SystemConfiguration getSystemConfiguration() {
        return systemConfiguration;
    }

    public EnvironmentConfiguration getEnvironmentConfiguration() {
        return environmentConfiguration;
    }

    public InmemoryConfiguration getExternalConfiguration() {
        return externalConfiguration;
    }

    public InmemoryConfiguration getAppExternalConfiguration() {
        return appExternalConfiguration;
    }

    public InmemoryConfiguration getAppConfiguration() {
        return appConfiguration;
    }

    public String getLocalMigrationRule() {
        return localMigrationRule;
    }

    public void refreshClassLoaders() {
        propertiesConfiguration.refresh();
        loadMigrationRule();
    }

}
