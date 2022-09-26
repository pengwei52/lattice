package org.hiforce.lattice.runtime;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.hifforce.lattice.cache.LatticeCacheFactory;
import org.hifforce.lattice.exception.LatticeRuntimeException;
import org.hifforce.lattice.message.Message;
import org.hifforce.lattice.message.MessageCode;
import org.hifforce.lattice.model.ability.IAbility;
import org.hifforce.lattice.model.ability.IBusinessExt;
import org.hifforce.lattice.model.business.IBusiness;
import org.hifforce.lattice.model.business.IProduct;
import org.hifforce.lattice.model.business.TemplateType;
import org.hifforce.lattice.model.config.*;
import org.hifforce.lattice.model.config.builder.BusinessConfigBuilder;
import org.hifforce.lattice.model.register.AbilitySpec;
import org.hifforce.lattice.model.register.BusinessSpec;
import org.hifforce.lattice.model.register.ProductSpec;
import org.hifforce.lattice.model.register.RealizationSpec;
import org.hifforce.lattice.utils.BizCodeUtils;
import org.hiforce.lattice.runtime.ability.register.AbilityBuildRequest;
import org.hiforce.lattice.runtime.ability.register.AbilityRegister;
import org.hiforce.lattice.runtime.ability.register.TemplateRegister;
import org.hiforce.lattice.runtime.cache.LatticeRuntimeCache;
import org.hiforce.lattice.runtime.template.LatticeTemplateManager;
import org.hiforce.lattice.runtime.utils.ClassLoaderUtil;
import org.hiforce.lattice.runtime.utils.ClassPathScanHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Rocky Yu
 * @since 2022/9/16
 */
@Slf4j
@SuppressWarnings("unused")
public class Lattice {


    private static Lattice instance;

    @Getter
    @Setter
    private String uuid = UUID.randomUUID().toString();

    @Getter
    private boolean initialized = false;

    /**
     * The business config not necessary.
     */
    @Getter
    @Setter
    private boolean simpleMode = false;

    @Getter
    private final List<BusinessConfig> businessConfigs = Lists.newArrayList();

    @Getter
    private final LatticeTemplateManager templateManager = new LatticeTemplateManager();

    @Getter
    private final List<AbilitySpec> registeredAbilities = Lists.newArrayList();


    @Getter
    private final LatticeRuntimeCache latticeRuntimeCache = (LatticeRuntimeCache) LatticeCacheFactory.getInstance()
            .getRuntimeCache(new LatticeRuntimeCache());

    private Lattice() {

    }

    public static Lattice getInstance() {
        if (null == instance) {
            instance = new Lattice();
        }
        return instance;
    }

    public final void start() {
        registerAbilities();//Register the Ability Instances during runtime.
        registerRealizations();//Register the business extension realization during runtime.
        registerBusinesses();
        registerProducts();
        buildBusinessConfig();
        initLatticeCache();
        initialized = true;
    }

    private void initLatticeCache() {
        getLatticeRuntimeCache().buildExtensionRunnerCache();
        initErrorMessageCode();
        ClassPathScanHandler.clearCache();
    }

    private void initErrorMessageCode() {
        Message.clean();
        MessageCode.init();
    }

    public BusinessConfig getBusinessConfigByBizCode(String bizCode) {
        BusinessConfig config = businessConfigs.stream().filter(p -> StringUtils.equals(bizCode, p.getBizCode()))
                .findFirst().orElse(null);
        if (null == config) {
            return null;
        }
        return new ReadonlyBusinessConfig(config.getBizCode(), config.getPriority(),
                config.getProducts(), config.getExtensions());
    }


    private void buildBusinessConfig() {
        if (isSimpleMode()) {
            //auto-config business and products.
            autoBuildBusinessConfig();
        }
    }

    public void addBusinessConfig(BusinessConfig config) {
        if (null == config) {
            throw new LatticeRuntimeException("LATTICE-CORE-RT-0015");
        }

        Message message = checkBusinessConfig(config);
        if (null != message) {
            throw new LatticeRuntimeException(message);
        }

        businessConfigs.stream()
                .filter(p -> StringUtils.equals(config.getBizCode(), p.getBizCode()))
                .findFirst().ifPresent(businessConfigs::remove);
        businessConfigs.add(config);
    }

    private Message checkBusinessConfig(BusinessConfig config) {
        if (StringUtils.isEmpty(config.getBizCode())) {
            return Message.code("LATTICE-CORE-RT-0014");
        }
        return null;
    }

    private void autoBuildBusinessConfig() {
        List<ProductConfig> productConfigs = getAllRegisteredProducts().stream()
                .map(this::buildProductConfig)
                .collect(Collectors.toList());
        for (BusinessSpec businessSpec : getAllRegisteredBusinesses()) {
            List<ExtPriorityConfig> priorityConfigs = businessSpec.getRealizations().stream()
                    .flatMap(p -> autoBuildPriorityConfig(businessSpec, p).stream())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            BusinessConfig businessConfig = BusinessConfigBuilder.builder()
                    .priority(businessSpec.getPriority())
                    .bizCode(businessSpec.getCode())
                    .install(productConfigs)
                    .extension(priorityConfigs)
                    .build();
            businessConfigs.add(businessConfig);
            businessConfigs.sort(Comparator.comparingInt(BusinessConfig::getPriority));
        }
    }

    private void autoMakeupPriorityConfig(BusinessConfig businessConfig, List<ProductSpec> products) {
        Map<String, ExtPriorityConfig> priorityConfigHashMap = Maps.newHashMap();
        for (ProductSpec spec : products) {
            List<String> extCodes = spec.getRealizations().stream()
                    .flatMap(p -> p.getExtensionCodes().stream())
                    .filter(businessConfig::notContainExtCode)
                    .collect(Collectors.toList());
            if (CollectionUtils.isEmpty(extCodes)) {
                continue;
            }
            for (String extCode : extCodes) {
                ExtPriorityConfig priorityConfig = priorityConfigHashMap.get(extCode);
                if (null == priorityConfig) {
                    priorityConfig = new ExtPriorityConfig();
                    priorityConfig.setExtCode(extCode);
                    priorityConfigHashMap.put(extCode, priorityConfig);
                }
                priorityConfig.getPriorities().add(ExtPriority.of(spec.getCode(), TemplateType.PRODUCT));
            }
        }
        priorityConfigHashMap.values().forEach(p -> businessConfig.getExtensions().add(p));
    }

    private List<ExtPriorityConfig> autoBuildPriorityConfig(BusinessSpec business, RealizationSpec realization) {
        List<ExtPriorityConfig> configs = Lists.newArrayList();
        for (String extCode : realization.getExtensionCodes()) {
            List<ProductSpec> products = getAllRegisteredProducts().stream()
                    .filter(p -> p.getRealizations().stream().
                            anyMatch(real -> real.getExtensionCodes().contains(extCode)))
                    .collect(Collectors.toList());
            if (CollectionUtils.isEmpty(products)) {
                continue;
            }
            List<ExtPriority> extPriorities = Lists.newArrayList();
            extPriorities.addAll(products.stream()
                    .map(this::buildExtPriority).collect(Collectors.toList()));
            extPriorities.add(ExtPriority.of(business.getCode(), TemplateType.BUSINESS));
            ExtPriorityConfig priorityConfig = new ExtPriorityConfig();
            priorityConfig.setExtCode(extCode);
            priorityConfig.getPriorities().addAll(extPriorities);
            configs.add(priorityConfig);
        }
        return configs;
    }

    private ExtPriority buildExtPriority(ProductSpec productSpec) {
        return ExtPriority.of(productSpec.getCode(), TemplateType.PRODUCT);
    }

    private ProductConfig buildProductConfig(ProductSpec productSpec) {
        return ProductConfig.of(productSpec.getCode());
    }


    public Collection<AbilitySpec> getAllRegisteredAbilities() {
        return Lattice.getInstance().getLatticeRuntimeCache().getAllCachedAbilities();
    }

    public List<ProductSpec> getAllRegisteredProducts() {
        return TemplateRegister.getInstance().getProducts();
    }

    public List<BusinessSpec> getAllRegisteredBusinesses() {
        return TemplateRegister.getInstance().getBusinesses();
    }

    public List<RealizationSpec> getAllRegisteredRealizations() {
        return TemplateRegister.getInstance().getRealizations();
    }

    public BusinessSpec getRegisteredBusinessByCode(String code) {
        return TemplateRegister.getInstance().getBusinesses().stream()
                .filter(p -> StringUtils.equals(code, p.getCode()))
                .findFirst().orElse(null);
    }

    public ProductSpec getRegisteredProductByCode(String code) {
        return TemplateRegister.getInstance().getProducts().stream()
                .filter(p -> StringUtils.equals(code, p.getCode()))
                .findFirst().orElse(null);
    }


    @SuppressWarnings("rawtypes")
    public static Set<Class> getServiceProviderClasses(String spiClassName) {
        Set<Class> classList = Sets.newHashSet();
        try {
            List<ClassLoader> classLoaders = Lists.newArrayList(Thread.currentThread().getContextClassLoader());
            for (ClassLoader classLoader : classLoaders) {
                Enumeration<URL> enumeration = classLoader.getResources("META-INF/services/" + spiClassName);
                while (enumeration.hasMoreElements()) {
                    URL url = enumeration.nextElement();
                    List<String> classNames = loadSpiFileContent(url);
                    for (String className : classNames) {
                        try {
                            classList.add(Class.forName(className));
                        } catch (Exception e) {
                            log.warn(e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn(e.getMessage(), e);
        }
        return classList;
    }

    private static List<String> loadSpiFileContent(URL url) {
        List<String> contentList = new ArrayList<>();
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
            String eachLine;
            while (StringUtils.isNotBlank(eachLine = bufferedReader.readLine())) {
                eachLine = eachLine.trim();
                contentList.add(eachLine);
            }
        } catch (Exception e) {
            log.warn(e.getMessage(), e);
        }
        return contentList;
    }

    @SuppressWarnings("rawtypes")
    private void registerRealizations() {
        Set<Class> classSet = getServiceProviderClasses(IBusinessExt.class.getName());
        TemplateRegister.getInstance().registerRealizations(classSet);
    }

    @SuppressWarnings("rawtypes")
    private void registerAbilities() {
        Set<Class> abilityClasses = getServiceProviderClasses(IAbility.class.getName());
        registeredAbilities.addAll(AbilityRegister.getInstance()
                .register(new AbilityBuildRequest(null, mergeAbilityInstancePackage(abilityClasses))));
    }

    @SuppressWarnings("rawtypes")
    private void registerBusinesses() {
        Set<Class> classSet = getServiceProviderClasses(IBusiness.class.getName());
        TemplateRegister.getInstance().registerBusinesses(classSet);
    }

    @SuppressWarnings("rawtypes")
    private void registerProducts() {
        Set<Class> classSet = getServiceProviderClasses(IProduct.class.getName());
        TemplateRegister.getInstance().registerProducts(classSet);
    }

    @SuppressWarnings("rawtypes")
    private Set<Class> mergeAbilityInstancePackage(Set<Class> abilityClasses) {
        Set<Class> classesSet = Sets.newHashSet(abilityClasses);
        Set<String> packageSet = abilityClasses.stream().map(p -> p.getPackage().getName()).collect(Collectors.toSet());
        for (String pkg : packageSet) {
            classesSet.addAll(ClassLoaderUtil.scanLatticeClasses(pkg));
        }
        return classesSet;
    }

    public RealizationSpec getRealizationSpecByCode(String code) {
        return TemplateRegister.getInstance().getRealizations()
                .stream().filter(p -> BizCodeUtils.isCodesMatched(p.getCode(), code))
                .findFirst().orElse(null);
    }

    public List<RealizationSpec> getAllRealizations() {
        return TemplateRegister.getInstance().getRealizations();
    }

}
