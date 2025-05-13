package org.gradle.accessors.dm;

import org.gradle.api.NonNullApi;
import org.gradle.api.artifacts.MinimalExternalModuleDependency;
import org.gradle.plugin.use.PluginDependency;
import org.gradle.api.artifacts.ExternalModuleDependencyBundle;
import org.gradle.api.artifacts.MutableVersionConstraint;
import org.gradle.api.provider.Provider;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.internal.catalog.AbstractExternalDependencyFactory;
import org.gradle.api.internal.catalog.DefaultVersionCatalog;
import java.util.Map;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.artifacts.dsl.CapabilityNotationParser;
import javax.inject.Inject;

/**
 * A catalog of dependencies accessible via the {@code libs} extension.
 */
@NonNullApi
public class LibrariesForLibs extends AbstractExternalDependencyFactory {

    private final AbstractExternalDependencyFactory owner = this;
    private final AccompanistLibraryAccessors laccForAccompanistLibraryAccessors = new AccompanistLibraryAccessors(owner);
    private final AndroidxLibraryAccessors laccForAndroidxLibraryAccessors = new AndroidxLibraryAccessors(owner);
    private final ComLibraryAccessors laccForComLibraryAccessors = new ComLibraryAccessors(owner);
    private final ConverterLibraryAccessors laccForConverterLibraryAccessors = new ConverterLibraryAccessors(owner);
    private final FirebaseLibraryAccessors laccForFirebaseLibraryAccessors = new FirebaseLibraryAccessors(owner);
    private final GoogleLibraryAccessors laccForGoogleLibraryAccessors = new GoogleLibraryAccessors(owner);
    private final HiltLibraryAccessors laccForHiltLibraryAccessors = new HiltLibraryAccessors(owner);
    private final IdentityLibraryAccessors laccForIdentityLibraryAccessors = new IdentityLibraryAccessors(owner);
    private final KotlinxLibraryAccessors laccForKotlinxLibraryAccessors = new KotlinxLibraryAccessors(owner);
    private final LoggingLibraryAccessors laccForLoggingLibraryAccessors = new LoggingLibraryAccessors(owner);
    private final MockitoLibraryAccessors laccForMockitoLibraryAccessors = new MockitoLibraryAccessors(owner);
    private final PlayLibraryAccessors laccForPlayLibraryAccessors = new PlayLibraryAccessors(owner);
    private final VersionAccessors vaccForVersionAccessors = new VersionAccessors(providers, config);
    private final BundleAccessors baccForBundleAccessors = new BundleAccessors(objects, providers, config, attributesFactory, capabilityNotationParser);
    private final PluginAccessors paccForPluginAccessors = new PluginAccessors(providers, config);

    @Inject
    public LibrariesForLibs(DefaultVersionCatalog config, ProviderFactory providers, ObjectFactory objects, ImmutableAttributesFactory attributesFactory, CapabilityNotationParser capabilityNotationParser) {
        super(config, providers, objects, attributesFactory, capabilityNotationParser);
    }

    /**
     * Dependency provider for <b>calendar</b> with <b>com.maxkeppeler.sheets-compose-dialogs:calendar</b> coordinates and
     * with version reference <b>calendar</b>
     * <p>
     * This dependency was declared in catalog libs.versions.toml
     */
    public Provider<MinimalExternalModuleDependency> getCalendar() {
        return create("calendar");
    }

    /**
     * Dependency provider for <b>clock</b> with <b>com.maxkeppeler.sheets-compose-dialogs:clock</b> coordinates and
     * with version reference <b>calendar</b>
     * <p>
     * This dependency was declared in catalog libs.versions.toml
     */
    public Provider<MinimalExternalModuleDependency> getClock() {
        return create("clock");
    }

    /**
     * Dependency provider for <b>core</b> with <b>com.maxkeppeler.sheets-compose-dialogs:core</b> coordinates and
     * with version reference <b>core</b>
     * <p>
     * This dependency was declared in catalog libs.versions.toml
     */
    public Provider<MinimalExternalModuleDependency> getCore() {
        return create("core");
    }

    /**
     * Dependency provider for <b>junit</b> with <b>junit:junit</b> coordinates and
     * with version reference <b>junit</b>
     * <p>
     * This dependency was declared in catalog libs.versions.toml
     */
    public Provider<MinimalExternalModuleDependency> getJunit() {
        return create("junit");
    }

    /**
     * Dependency provider for <b>okhttp</b> with <b>com.squareup.okhttp3:okhttp</b> coordinates and
     * with version reference <b>okhttp</b>
     * <p>
     * This dependency was declared in catalog libs.versions.toml
     */
    public Provider<MinimalExternalModuleDependency> getOkhttp() {
        return create("okhttp");
    }

    /**
     * Dependency provider for <b>retrofit</b> with <b>com.squareup.retrofit2:retrofit</b> coordinates and
     * with version reference <b>converter.gson</b>
     * <p>
     * This dependency was declared in catalog libs.versions.toml
     */
    public Provider<MinimalExternalModuleDependency> getRetrofit() {
        return create("retrofit");
    }

    /**
     * Dependency provider for <b>state</b> with <b>com.maxkeppeler.sheets-compose-dialogs:state</b> coordinates and
     * with version reference <b>calendar</b>
     * <p>
     * This dependency was declared in catalog libs.versions.toml
     */
    public Provider<MinimalExternalModuleDependency> getState() {
        return create("state");
    }

    /**
     * Group of libraries at <b>accompanist</b>
     */
    public AccompanistLibraryAccessors getAccompanist() {
        return laccForAccompanistLibraryAccessors;
    }

    /**
     * Group of libraries at <b>androidx</b>
     */
    public AndroidxLibraryAccessors getAndroidx() {
        return laccForAndroidxLibraryAccessors;
    }

    /**
     * Group of libraries at <b>com</b>
     */
    public ComLibraryAccessors getCom() {
        return laccForComLibraryAccessors;
    }

    /**
     * Group of libraries at <b>converter</b>
     */
    public ConverterLibraryAccessors getConverter() {
        return laccForConverterLibraryAccessors;
    }

    /**
     * Group of libraries at <b>firebase</b>
     */
    public FirebaseLibraryAccessors getFirebase() {
        return laccForFirebaseLibraryAccessors;
    }

    /**
     * Group of libraries at <b>google</b>
     */
    public GoogleLibraryAccessors getGoogle() {
        return laccForGoogleLibraryAccessors;
    }

    /**
     * Group of libraries at <b>hilt</b>
     */
    public HiltLibraryAccessors getHilt() {
        return laccForHiltLibraryAccessors;
    }

    /**
     * Group of libraries at <b>identity</b>
     */
    public IdentityLibraryAccessors getIdentity() {
        return laccForIdentityLibraryAccessors;
    }

    /**
     * Group of libraries at <b>kotlinx</b>
     */
    public KotlinxLibraryAccessors getKotlinx() {
        return laccForKotlinxLibraryAccessors;
    }

    /**
     * Group of libraries at <b>logging</b>
     */
    public LoggingLibraryAccessors getLogging() {
        return laccForLoggingLibraryAccessors;
    }

    /**
     * Group of libraries at <b>mockito</b>
     */
    public MockitoLibraryAccessors getMockito() {
        return laccForMockitoLibraryAccessors;
    }

    /**
     * Group of libraries at <b>play</b>
     */
    public PlayLibraryAccessors getPlay() {
        return laccForPlayLibraryAccessors;
    }

    /**
     * Group of versions at <b>versions</b>
     */
    public VersionAccessors getVersions() {
        return vaccForVersionAccessors;
    }

    /**
     * Group of bundles at <b>bundles</b>
     */
    public BundleAccessors getBundles() {
        return baccForBundleAccessors;
    }

    /**
     * Group of plugins at <b>plugins</b>
     */
    public PluginAccessors getPlugins() {
        return paccForPluginAccessors;
    }

    public static class AccompanistLibraryAccessors extends SubDependencyFactory {
        private final AccompanistNavigationLibraryAccessors laccForAccompanistNavigationLibraryAccessors = new AccompanistNavigationLibraryAccessors(owner);

        public AccompanistLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>accompanist.navigation</b>
         */
        public AccompanistNavigationLibraryAccessors getNavigation() {
            return laccForAccompanistNavigationLibraryAccessors;
        }

    }

    public static class AccompanistNavigationLibraryAccessors extends SubDependencyFactory {

        public AccompanistNavigationLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>animation</b> with <b>com.google.accompanist:accompanist-navigation-animation</b> coordinates and
         * with version reference <b>accompanist.navigation.animation</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getAnimation() {
            return create("accompanist.navigation.animation");
        }

    }

    public static class AndroidxLibraryAccessors extends SubDependencyFactory {
        private final AndroidxActivityLibraryAccessors laccForAndroidxActivityLibraryAccessors = new AndroidxActivityLibraryAccessors(owner);
        private final AndroidxAnimationLibraryAccessors laccForAndroidxAnimationLibraryAccessors = new AndroidxAnimationLibraryAccessors(owner);
        private final AndroidxComposeLibraryAccessors laccForAndroidxComposeLibraryAccessors = new AndroidxComposeLibraryAccessors(owner);
        private final AndroidxCoreLibraryAccessors laccForAndroidxCoreLibraryAccessors = new AndroidxCoreLibraryAccessors(owner);
        private final AndroidxDatastoreLibraryAccessors laccForAndroidxDatastoreLibraryAccessors = new AndroidxDatastoreLibraryAccessors(owner);
        private final AndroidxEspressoLibraryAccessors laccForAndroidxEspressoLibraryAccessors = new AndroidxEspressoLibraryAccessors(owner);
        private final AndroidxHiltLibraryAccessors laccForAndroidxHiltLibraryAccessors = new AndroidxHiltLibraryAccessors(owner);
        private final AndroidxLifecycleLibraryAccessors laccForAndroidxLifecycleLibraryAccessors = new AndroidxLifecycleLibraryAccessors(owner);
        private final AndroidxNavigationLibraryAccessors laccForAndroidxNavigationLibraryAccessors = new AndroidxNavigationLibraryAccessors(owner);
        private final AndroidxPagingLibraryAccessors laccForAndroidxPagingLibraryAccessors = new AndroidxPagingLibraryAccessors(owner);
        private final AndroidxRoomLibraryAccessors laccForAndroidxRoomLibraryAccessors = new AndroidxRoomLibraryAccessors(owner);
        private final AndroidxRuntimeLibraryAccessors laccForAndroidxRuntimeLibraryAccessors = new AndroidxRuntimeLibraryAccessors(owner);
        private final AndroidxTestLibraryAccessors laccForAndroidxTestLibraryAccessors = new AndroidxTestLibraryAccessors(owner);
        private final AndroidxUiLibraryAccessors laccForAndroidxUiLibraryAccessors = new AndroidxUiLibraryAccessors(owner);
        private final AndroidxWorkLibraryAccessors laccForAndroidxWorkLibraryAccessors = new AndroidxWorkLibraryAccessors(owner);

        public AndroidxLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>junit</b> with <b>androidx.test.ext:junit</b> coordinates and
         * with version reference <b>androidx.test.ext.junit</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getJunit() {
            return create("androidx.junit");
        }

        /**
         * Dependency provider for <b>material3</b> with <b>androidx.compose.material3:material3</b> coordinates and
         * with version reference <b>material3</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getMaterial3() {
            return create("androidx.material3");
        }

        /**
         * Dependency provider for <b>multidex</b> with <b>androidx.multidex:multidex</b> coordinates and
         * with version reference <b>multidex</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getMultidex() {
            return create("androidx.multidex");
        }

        /**
         * Group of libraries at <b>androidx.activity</b>
         */
        public AndroidxActivityLibraryAccessors getActivity() {
            return laccForAndroidxActivityLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.animation</b>
         */
        public AndroidxAnimationLibraryAccessors getAnimation() {
            return laccForAndroidxAnimationLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.compose</b>
         */
        public AndroidxComposeLibraryAccessors getCompose() {
            return laccForAndroidxComposeLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.core</b>
         */
        public AndroidxCoreLibraryAccessors getCore() {
            return laccForAndroidxCoreLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.datastore</b>
         */
        public AndroidxDatastoreLibraryAccessors getDatastore() {
            return laccForAndroidxDatastoreLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.espresso</b>
         */
        public AndroidxEspressoLibraryAccessors getEspresso() {
            return laccForAndroidxEspressoLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.hilt</b>
         */
        public AndroidxHiltLibraryAccessors getHilt() {
            return laccForAndroidxHiltLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.lifecycle</b>
         */
        public AndroidxLifecycleLibraryAccessors getLifecycle() {
            return laccForAndroidxLifecycleLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.navigation</b>
         */
        public AndroidxNavigationLibraryAccessors getNavigation() {
            return laccForAndroidxNavigationLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.paging</b>
         */
        public AndroidxPagingLibraryAccessors getPaging() {
            return laccForAndroidxPagingLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.room</b>
         */
        public AndroidxRoomLibraryAccessors getRoom() {
            return laccForAndroidxRoomLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.runtime</b>
         */
        public AndroidxRuntimeLibraryAccessors getRuntime() {
            return laccForAndroidxRuntimeLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.test</b>
         */
        public AndroidxTestLibraryAccessors getTest() {
            return laccForAndroidxTestLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.ui</b>
         */
        public AndroidxUiLibraryAccessors getUi() {
            return laccForAndroidxUiLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.work</b>
         */
        public AndroidxWorkLibraryAccessors getWork() {
            return laccForAndroidxWorkLibraryAccessors;
        }

    }

    public static class AndroidxActivityLibraryAccessors extends SubDependencyFactory {
        private final AndroidxActivityComposeLibraryAccessors laccForAndroidxActivityComposeLibraryAccessors = new AndroidxActivityComposeLibraryAccessors(owner);

        public AndroidxActivityLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>androidx.activity:activity-ktx</b> coordinates and
         * with version reference <b>activity.ktx</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("androidx.activity.ktx");
        }

        /**
         * Group of libraries at <b>androidx.activity.compose</b>
         */
        public AndroidxActivityComposeLibraryAccessors getCompose() {
            return laccForAndroidxActivityComposeLibraryAccessors;
        }

    }

    public static class AndroidxActivityComposeLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public AndroidxActivityComposeLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>compose</b> with <b>androidx.activity:activity-compose</b> coordinates and
         * with version reference <b>activity.compose</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> asProvider() {
            return create("androidx.activity.compose");
        }

        /**
         * Dependency provider for <b>v191</b> with <b>androidx.activity:activity-compose</b> coordinates and
         * with version reference <b>androidx.activity.compose</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getV191() {
            return create("androidx.activity.compose.v191");
        }

    }

    public static class AndroidxAnimationLibraryAccessors extends SubDependencyFactory {
        private final AndroidxAnimationGraphicsLibraryAccessors laccForAndroidxAnimationGraphicsLibraryAccessors = new AndroidxAnimationGraphicsLibraryAccessors(owner);

        public AndroidxAnimationLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>v154</b> with <b>androidx.compose.animation:animation</b> coordinates and
         * with version reference <b>animation</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getV154() {
            return create("androidx.animation.v154");
        }

        /**
         * Group of libraries at <b>androidx.animation.graphics</b>
         */
        public AndroidxAnimationGraphicsLibraryAccessors getGraphics() {
            return laccForAndroidxAnimationGraphicsLibraryAccessors;
        }

    }

    public static class AndroidxAnimationGraphicsLibraryAccessors extends SubDependencyFactory {

        public AndroidxAnimationGraphicsLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>v154</b> with <b>androidx.compose.animation:animation-graphics</b> coordinates and
         * with version reference <b>animation</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getV154() {
            return create("androidx.animation.graphics.v154");
        }

    }

    public static class AndroidxComposeLibraryAccessors extends SubDependencyFactory {
        private final AndroidxComposeMaterialLibraryAccessors laccForAndroidxComposeMaterialLibraryAccessors = new AndroidxComposeMaterialLibraryAccessors(owner);
        private final AndroidxComposeUiLibraryAccessors laccForAndroidxComposeUiLibraryAccessors = new AndroidxComposeUiLibraryAccessors(owner);

        public AndroidxComposeLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>animation</b> with <b>androidx.compose.animation:animation</b> coordinates and
         * with version <b>1.5.4</b>
         * <p>
         * This dependency was declared in settings file 'settings.gradle.kts'
         */
        public Provider<MinimalExternalModuleDependency> getAnimation() {
            return create("androidx.compose.animation");
        }

        /**
         * Dependency provider for <b>bom</b> with <b>androidx.compose:compose-bom</b> coordinates and
         * with version reference <b>compose.bom</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getBom() {
            return create("androidx.compose.bom");
        }

        /**
         * Dependency provider for <b>foundation</b> with <b>androidx.compose.foundation:foundation</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in settings file 'settings.gradle.kts'
         */
        public Provider<MinimalExternalModuleDependency> getFoundation() {
            return create("androidx.compose.foundation");
        }

        /**
         * Dependency provider for <b>material3</b> with <b>androidx.compose.material3:material3</b> coordinates and
         * with version <b>1.2.0</b>
         * <p>
         * This dependency was declared in settings file 'settings.gradle.kts'
         */
        public Provider<MinimalExternalModuleDependency> getMaterial3() {
            return create("androidx.compose.material3");
        }

        /**
         * Dependency provider for <b>runtime</b> with <b>androidx.compose.runtime:runtime</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in settings file 'settings.gradle.kts'
         */
        public Provider<MinimalExternalModuleDependency> getRuntime() {
            return create("androidx.compose.runtime");
        }

        /**
         * Group of libraries at <b>androidx.compose.material</b>
         */
        public AndroidxComposeMaterialLibraryAccessors getMaterial() {
            return laccForAndroidxComposeMaterialLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.compose.ui</b>
         */
        public AndroidxComposeUiLibraryAccessors getUi() {
            return laccForAndroidxComposeUiLibraryAccessors;
        }

    }

    public static class AndroidxComposeMaterialLibraryAccessors extends SubDependencyFactory {
        private final AndroidxComposeMaterialIconsLibraryAccessors laccForAndroidxComposeMaterialIconsLibraryAccessors = new AndroidxComposeMaterialIconsLibraryAccessors(owner);

        public AndroidxComposeMaterialLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>androidx.compose.material.icons</b>
         */
        public AndroidxComposeMaterialIconsLibraryAccessors getIcons() {
            return laccForAndroidxComposeMaterialIconsLibraryAccessors;
        }

    }

    public static class AndroidxComposeMaterialIconsLibraryAccessors extends SubDependencyFactory {

        public AndroidxComposeMaterialIconsLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>extended</b> with <b>androidx.compose.material:material-icons-extended</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in settings file 'settings.gradle.kts'
         */
        public Provider<MinimalExternalModuleDependency> getExtended() {
            return create("androidx.compose.material.icons.extended");
        }

    }

    public static class AndroidxComposeUiLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {
        private final AndroidxComposeUiToolingLibraryAccessors laccForAndroidxComposeUiToolingLibraryAccessors = new AndroidxComposeUiToolingLibraryAccessors(owner);

        public AndroidxComposeUiLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ui</b> with <b>androidx.compose.ui:ui</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in settings file 'settings.gradle.kts'
         */
        public Provider<MinimalExternalModuleDependency> asProvider() {
            return create("androidx.compose.ui");
        }

        /**
         * Dependency provider for <b>graphics</b> with <b>androidx.compose.ui:ui-graphics</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in settings file 'settings.gradle.kts'
         */
        public Provider<MinimalExternalModuleDependency> getGraphics() {
            return create("androidx.compose.ui.graphics");
        }

        /**
         * Group of libraries at <b>androidx.compose.ui.tooling</b>
         */
        public AndroidxComposeUiToolingLibraryAccessors getTooling() {
            return laccForAndroidxComposeUiToolingLibraryAccessors;
        }

    }

    public static class AndroidxComposeUiToolingLibraryAccessors extends SubDependencyFactory {

        public AndroidxComposeUiToolingLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>preview</b> with <b>androidx.compose.ui:ui-tooling-preview</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in settings file 'settings.gradle.kts'
         */
        public Provider<MinimalExternalModuleDependency> getPreview() {
            return create("androidx.compose.ui.tooling.preview");
        }

    }

    public static class AndroidxCoreLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public AndroidxCoreLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>core</b> with <b>androidx.core:core</b> coordinates and
         * with version reference <b>core.ktx</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> asProvider() {
            return create("androidx.core");
        }

        /**
         * Dependency provider for <b>ktx</b> with <b>androidx.core:core-ktx</b> coordinates and
         * with version reference <b>core.ktx</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("androidx.core.ktx");
        }

    }

    public static class AndroidxDatastoreLibraryAccessors extends SubDependencyFactory {
        private final AndroidxDatastorePreferencesLibraryAccessors laccForAndroidxDatastorePreferencesLibraryAccessors = new AndroidxDatastorePreferencesLibraryAccessors(owner);

        public AndroidxDatastoreLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>androidx.datastore.preferences</b>
         */
        public AndroidxDatastorePreferencesLibraryAccessors getPreferences() {
            return laccForAndroidxDatastorePreferencesLibraryAccessors;
        }

    }

    public static class AndroidxDatastorePreferencesLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public AndroidxDatastorePreferencesLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>preferences</b> with <b>androidx.datastore:datastore-preferences</b> coordinates and
         * with version reference <b>datastore.preferences.core</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> asProvider() {
            return create("androidx.datastore.preferences");
        }

        /**
         * Dependency provider for <b>core</b> with <b>androidx.datastore:datastore-preferences-core</b> coordinates and
         * with version reference <b>datastore.preferences.core</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getCore() {
            return create("androidx.datastore.preferences.core");
        }

    }

    public static class AndroidxEspressoLibraryAccessors extends SubDependencyFactory {

        public AndroidxEspressoLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>core</b> with <b>androidx.test.espresso:espresso-core</b> coordinates and
         * with version reference <b>espresso.core</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getCore() {
            return create("androidx.espresso.core");
        }

    }

    public static class AndroidxHiltLibraryAccessors extends SubDependencyFactory {
        private final AndroidxHiltNavigationLibraryAccessors laccForAndroidxHiltNavigationLibraryAccessors = new AndroidxHiltNavigationLibraryAccessors(owner);

        public AndroidxHiltLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>compiler</b> with <b>androidx.hilt:hilt-compiler</b> coordinates and
         * with version reference <b>hilt.navigation.compose</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getCompiler() {
            return create("androidx.hilt.compiler");
        }

        /**
         * Dependency provider for <b>work</b> with <b>androidx.hilt:hilt-work</b> coordinates and
         * with version reference <b>hilt.navigation.compose</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getWork() {
            return create("androidx.hilt.work");
        }

        /**
         * Group of libraries at <b>androidx.hilt.navigation</b>
         */
        public AndroidxHiltNavigationLibraryAccessors getNavigation() {
            return laccForAndroidxHiltNavigationLibraryAccessors;
        }

    }

    public static class AndroidxHiltNavigationLibraryAccessors extends SubDependencyFactory {
        private final AndroidxHiltNavigationComposeLibraryAccessors laccForAndroidxHiltNavigationComposeLibraryAccessors = new AndroidxHiltNavigationComposeLibraryAccessors(owner);

        public AndroidxHiltNavigationLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>androidx.hilt.navigation.compose</b>
         */
        public AndroidxHiltNavigationComposeLibraryAccessors getCompose() {
            return laccForAndroidxHiltNavigationComposeLibraryAccessors;
        }

    }

    public static class AndroidxHiltNavigationComposeLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public AndroidxHiltNavigationComposeLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>compose</b> with <b>androidx.hilt:hilt-navigation-compose</b> coordinates and
         * with version reference <b>hilt.navigation.compose</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> asProvider() {
            return create("androidx.hilt.navigation.compose");
        }

        /**
         * Dependency provider for <b>v120</b> with <b>androidx.hilt:hilt-navigation-compose</b> coordinates and
         * with version reference <b>hilt.navigation.compose</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getV120() {
            return create("androidx.hilt.navigation.compose.v120");
        }

    }

    public static class AndroidxLifecycleLibraryAccessors extends SubDependencyFactory {
        private final AndroidxLifecycleRuntimeLibraryAccessors laccForAndroidxLifecycleRuntimeLibraryAccessors = new AndroidxLifecycleRuntimeLibraryAccessors(owner);
        private final AndroidxLifecycleViewmodelLibraryAccessors laccForAndroidxLifecycleViewmodelLibraryAccessors = new AndroidxLifecycleViewmodelLibraryAccessors(owner);

        public AndroidxLifecycleLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>androidx.lifecycle.runtime</b>
         */
        public AndroidxLifecycleRuntimeLibraryAccessors getRuntime() {
            return laccForAndroidxLifecycleRuntimeLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.lifecycle.viewmodel</b>
         */
        public AndroidxLifecycleViewmodelLibraryAccessors getViewmodel() {
            return laccForAndroidxLifecycleViewmodelLibraryAccessors;
        }

    }

    public static class AndroidxLifecycleRuntimeLibraryAccessors extends SubDependencyFactory {
        private final AndroidxLifecycleRuntimeKtxLibraryAccessors laccForAndroidxLifecycleRuntimeKtxLibraryAccessors = new AndroidxLifecycleRuntimeKtxLibraryAccessors(owner);

        public AndroidxLifecycleRuntimeLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>androidx.lifecycle.runtime.ktx</b>
         */
        public AndroidxLifecycleRuntimeKtxLibraryAccessors getKtx() {
            return laccForAndroidxLifecycleRuntimeKtxLibraryAccessors;
        }

    }

    public static class AndroidxLifecycleRuntimeKtxLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public AndroidxLifecycleRuntimeKtxLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>androidx.lifecycle:lifecycle-runtime-ktx</b> coordinates and
         * with version reference <b>lifecycle.runtime.ktx</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> asProvider() {
            return create("androidx.lifecycle.runtime.ktx");
        }

        /**
         * Dependency provider for <b>v286</b> with <b>androidx.lifecycle:lifecycle-runtime-ktx</b> coordinates and
         * with version reference <b>lifecycle.runtime.ktx.version</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getV286() {
            return create("androidx.lifecycle.runtime.ktx.v286");
        }

    }

    public static class AndroidxLifecycleViewmodelLibraryAccessors extends SubDependencyFactory {
        private final AndroidxLifecycleViewmodelComposeLibraryAccessors laccForAndroidxLifecycleViewmodelComposeLibraryAccessors = new AndroidxLifecycleViewmodelComposeLibraryAccessors(owner);

        public AndroidxLifecycleViewmodelLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>androidx.lifecycle:lifecycle-viewmodel-ktx</b> coordinates and
         * with version reference <b>lifecycle.runtime.ktx.version</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("androidx.lifecycle.viewmodel.ktx");
        }

        /**
         * Group of libraries at <b>androidx.lifecycle.viewmodel.compose</b>
         */
        public AndroidxLifecycleViewmodelComposeLibraryAccessors getCompose() {
            return laccForAndroidxLifecycleViewmodelComposeLibraryAccessors;
        }

    }

    public static class AndroidxLifecycleViewmodelComposeLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public AndroidxLifecycleViewmodelComposeLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>compose</b> with <b>androidx.lifecycle:lifecycle-viewmodel-compose</b> coordinates and
         * with version reference <b>lifecycle.viewmodel.compose</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> asProvider() {
            return create("androidx.lifecycle.viewmodel.compose");
        }

        /**
         * Dependency provider for <b>v284</b> with <b>androidx.lifecycle:lifecycle-viewmodel-compose</b> coordinates and
         * with version reference <b>androidx.lifecycle.viewmodel.compose</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getV284() {
            return create("androidx.lifecycle.viewmodel.compose.v284");
        }

    }

    public static class AndroidxNavigationLibraryAccessors extends SubDependencyFactory {
        private final AndroidxNavigationComposeLibraryAccessors laccForAndroidxNavigationComposeLibraryAccessors = new AndroidxNavigationComposeLibraryAccessors(owner);

        public AndroidxNavigationLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>androidx.navigation.compose</b>
         */
        public AndroidxNavigationComposeLibraryAccessors getCompose() {
            return laccForAndroidxNavigationComposeLibraryAccessors;
        }

    }

    public static class AndroidxNavigationComposeLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public AndroidxNavigationComposeLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>compose</b> with <b>androidx.navigation:navigation-compose</b> coordinates and
         * with version reference <b>navigation.compose</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> asProvider() {
            return create("androidx.navigation.compose");
        }

        /**
         * Dependency provider for <b>v281</b> with <b>androidx.navigation:navigation-compose</b> coordinates and
         * with version reference <b>navigation.compose.version</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getV281() {
            return create("androidx.navigation.compose.v281");
        }

    }

    public static class AndroidxPagingLibraryAccessors extends SubDependencyFactory {

        public AndroidxPagingLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>compose</b> with <b>androidx.paging:paging-compose</b> coordinates and
         * with version reference <b>paging.compose</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getCompose() {
            return create("androidx.paging.compose");
        }

    }

    public static class AndroidxRoomLibraryAccessors extends SubDependencyFactory {

        public AndroidxRoomLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>androidx.room:room-ktx</b> coordinates and
         * with version reference <b>room.ktx</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("androidx.room.ktx");
        }

    }

    public static class AndroidxRuntimeLibraryAccessors extends SubDependencyFactory {

        public AndroidxRuntimeLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>livedata</b> with <b>androidx.compose.runtime:runtime-livedata</b> coordinates and
         * with version reference <b>runtime.livedata</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getLivedata() {
            return create("androidx.runtime.livedata");
        }

    }

    public static class AndroidxTestLibraryAccessors extends SubDependencyFactory {
        private final AndroidxTestEspressoLibraryAccessors laccForAndroidxTestEspressoLibraryAccessors = new AndroidxTestEspressoLibraryAccessors(owner);
        private final AndroidxTestExtLibraryAccessors laccForAndroidxTestExtLibraryAccessors = new AndroidxTestExtLibraryAccessors(owner);

        public AndroidxTestLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>androidx.test.espresso</b>
         */
        public AndroidxTestEspressoLibraryAccessors getEspresso() {
            return laccForAndroidxTestEspressoLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.test.ext</b>
         */
        public AndroidxTestExtLibraryAccessors getExt() {
            return laccForAndroidxTestExtLibraryAccessors;
        }

    }

    public static class AndroidxTestEspressoLibraryAccessors extends SubDependencyFactory {

        public AndroidxTestEspressoLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>core</b> with <b>androidx.test.espresso:espresso-core</b> coordinates and
         * with version <b>3.5.1</b>
         * <p>
         * This dependency was declared in settings file 'settings.gradle.kts'
         */
        public Provider<MinimalExternalModuleDependency> getCore() {
            return create("androidx.test.espresso.core");
        }

    }

    public static class AndroidxTestExtLibraryAccessors extends SubDependencyFactory {

        public AndroidxTestExtLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>junit</b> with <b>androidx.test.ext:junit</b> coordinates and
         * with version <b>1.1.5</b>
         * <p>
         * This dependency was declared in settings file 'settings.gradle.kts'
         */
        public Provider<MinimalExternalModuleDependency> getJunit() {
            return create("androidx.test.ext.junit");
        }

    }

    public static class AndroidxUiLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {
        private final AndroidxUiTestLibraryAccessors laccForAndroidxUiTestLibraryAccessors = new AndroidxUiTestLibraryAccessors(owner);
        private final AndroidxUiToolingLibraryAccessors laccForAndroidxUiToolingLibraryAccessors = new AndroidxUiToolingLibraryAccessors(owner);

        public AndroidxUiLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ui</b> with <b>androidx.compose.ui:ui</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> asProvider() {
            return create("androidx.ui");
        }

        /**
         * Dependency provider for <b>graphics</b> with <b>androidx.compose.ui:ui-graphics</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getGraphics() {
            return create("androidx.ui.graphics");
        }

        /**
         * Dependency provider for <b>util</b> with <b>androidx.compose.ui:ui-util</b> coordinates and
         * with version reference <b>ui.util</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getUtil() {
            return create("androidx.ui.util");
        }

        /**
         * Group of libraries at <b>androidx.ui.test</b>
         */
        public AndroidxUiTestLibraryAccessors getTest() {
            return laccForAndroidxUiTestLibraryAccessors;
        }

        /**
         * Group of libraries at <b>androidx.ui.tooling</b>
         */
        public AndroidxUiToolingLibraryAccessors getTooling() {
            return laccForAndroidxUiToolingLibraryAccessors;
        }

    }

    public static class AndroidxUiTestLibraryAccessors extends SubDependencyFactory {

        public AndroidxUiTestLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>junit4</b> with <b>androidx.compose.ui:ui-test-junit4</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getJunit4() {
            return create("androidx.ui.test.junit4");
        }

        /**
         * Dependency provider for <b>manifest</b> with <b>androidx.compose.ui:ui-test-manifest</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getManifest() {
            return create("androidx.ui.test.manifest");
        }

    }

    public static class AndroidxUiToolingLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public AndroidxUiToolingLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>tooling</b> with <b>androidx.compose.ui:ui-tooling</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> asProvider() {
            return create("androidx.ui.tooling");
        }

        /**
         * Dependency provider for <b>preview</b> with <b>androidx.compose.ui:ui-tooling-preview</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getPreview() {
            return create("androidx.ui.tooling.preview");
        }

    }

    public static class AndroidxWorkLibraryAccessors extends SubDependencyFactory {
        private final AndroidxWorkRuntimeLibraryAccessors laccForAndroidxWorkRuntimeLibraryAccessors = new AndroidxWorkRuntimeLibraryAccessors(owner);

        public AndroidxWorkLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>androidx.work.runtime</b>
         */
        public AndroidxWorkRuntimeLibraryAccessors getRuntime() {
            return laccForAndroidxWorkRuntimeLibraryAccessors;
        }

    }

    public static class AndroidxWorkRuntimeLibraryAccessors extends SubDependencyFactory {

        public AndroidxWorkRuntimeLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>androidx.work:work-runtime-ktx</b> coordinates and
         * with version reference <b>work.runtime.ktx</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("androidx.work.runtime.ktx");
        }

    }

    public static class ComLibraryAccessors extends SubDependencyFactory {
        private final ComGoogleLibraryAccessors laccForComGoogleLibraryAccessors = new ComGoogleLibraryAccessors(owner);

        public ComLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>com.google</b>
         */
        public ComGoogleLibraryAccessors getGoogle() {
            return laccForComGoogleLibraryAccessors;
        }

    }

    public static class ComGoogleLibraryAccessors extends SubDependencyFactory {
        private final ComGoogleFirebaseLibraryAccessors laccForComGoogleFirebaseLibraryAccessors = new ComGoogleFirebaseLibraryAccessors(owner);

        public ComGoogleLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>com.google.firebase</b>
         */
        public ComGoogleFirebaseLibraryAccessors getFirebase() {
            return laccForComGoogleFirebaseLibraryAccessors;
        }

    }

    public static class ComGoogleFirebaseLibraryAccessors extends SubDependencyFactory {
        private final ComGoogleFirebaseFirebaseLibraryAccessors laccForComGoogleFirebaseFirebaseLibraryAccessors = new ComGoogleFirebaseFirebaseLibraryAccessors(owner);

        public ComGoogleFirebaseLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>com.google.firebase.firebase</b>
         */
        public ComGoogleFirebaseFirebaseLibraryAccessors getFirebase() {
            return laccForComGoogleFirebaseFirebaseLibraryAccessors;
        }

    }

    public static class ComGoogleFirebaseFirebaseLibraryAccessors extends SubDependencyFactory {
        private final ComGoogleFirebaseFirebaseAuthLibraryAccessors laccForComGoogleFirebaseFirebaseAuthLibraryAccessors = new ComGoogleFirebaseFirebaseAuthLibraryAccessors(owner);
        private final ComGoogleFirebaseFirebaseFirestoreLibraryAccessors laccForComGoogleFirebaseFirebaseFirestoreLibraryAccessors = new ComGoogleFirebaseFirebaseFirestoreLibraryAccessors(owner);
        private final ComGoogleFirebaseFirebaseMessagingLibraryAccessors laccForComGoogleFirebaseFirebaseMessagingLibraryAccessors = new ComGoogleFirebaseFirebaseMessagingLibraryAccessors(owner);

        public ComGoogleFirebaseFirebaseLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>com.google.firebase.firebase.auth</b>
         */
        public ComGoogleFirebaseFirebaseAuthLibraryAccessors getAuth() {
            return laccForComGoogleFirebaseFirebaseAuthLibraryAccessors;
        }

        /**
         * Group of libraries at <b>com.google.firebase.firebase.firestore</b>
         */
        public ComGoogleFirebaseFirebaseFirestoreLibraryAccessors getFirestore() {
            return laccForComGoogleFirebaseFirebaseFirestoreLibraryAccessors;
        }

        /**
         * Group of libraries at <b>com.google.firebase.firebase.messaging</b>
         */
        public ComGoogleFirebaseFirebaseMessagingLibraryAccessors getMessaging() {
            return laccForComGoogleFirebaseFirebaseMessagingLibraryAccessors;
        }

    }

    public static class ComGoogleFirebaseFirebaseAuthLibraryAccessors extends SubDependencyFactory {

        public ComGoogleFirebaseFirebaseAuthLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>com.google.firebase:firebase-auth-ktx</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("com.google.firebase.firebase.auth.ktx");
        }

    }

    public static class ComGoogleFirebaseFirebaseFirestoreLibraryAccessors extends SubDependencyFactory {

        public ComGoogleFirebaseFirebaseFirestoreLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>com.google.firebase:firebase-firestore-ktx</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("com.google.firebase.firebase.firestore.ktx");
        }

    }

    public static class ComGoogleFirebaseFirebaseMessagingLibraryAccessors extends SubDependencyFactory {

        public ComGoogleFirebaseFirebaseMessagingLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>com.google.firebase:firebase-messaging-ktx</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("com.google.firebase.firebase.messaging.ktx");
        }

    }

    public static class ConverterLibraryAccessors extends SubDependencyFactory {

        public ConverterLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>gson</b> with <b>com.squareup.retrofit2:converter-gson</b> coordinates and
         * with version reference <b>converter.gson</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getGson() {
            return create("converter.gson");
        }

    }

    public static class FirebaseLibraryAccessors extends SubDependencyFactory {
        private final FirebaseAnalyticsLibraryAccessors laccForFirebaseAnalyticsLibraryAccessors = new FirebaseAnalyticsLibraryAccessors(owner);
        private final FirebaseAuthLibraryAccessors laccForFirebaseAuthLibraryAccessors = new FirebaseAuthLibraryAccessors(owner);
        private final FirebaseBomLibraryAccessors laccForFirebaseBomLibraryAccessors = new FirebaseBomLibraryAccessors(owner);
        private final FirebaseCrashlyticsLibraryAccessors laccForFirebaseCrashlyticsLibraryAccessors = new FirebaseCrashlyticsLibraryAccessors(owner);
        private final FirebaseFirestoreLibraryAccessors laccForFirebaseFirestoreLibraryAccessors = new FirebaseFirestoreLibraryAccessors(owner);
        private final FirebaseFunctionsLibraryAccessors laccForFirebaseFunctionsLibraryAccessors = new FirebaseFunctionsLibraryAccessors(owner);
        private final FirebaseMessagingLibraryAccessors laccForFirebaseMessagingLibraryAccessors = new FirebaseMessagingLibraryAccessors(owner);

        public FirebaseLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>firebase.analytics</b>
         */
        public FirebaseAnalyticsLibraryAccessors getAnalytics() {
            return laccForFirebaseAnalyticsLibraryAccessors;
        }

        /**
         * Group of libraries at <b>firebase.auth</b>
         */
        public FirebaseAuthLibraryAccessors getAuth() {
            return laccForFirebaseAuthLibraryAccessors;
        }

        /**
         * Group of libraries at <b>firebase.bom</b>
         */
        public FirebaseBomLibraryAccessors getBom() {
            return laccForFirebaseBomLibraryAccessors;
        }

        /**
         * Group of libraries at <b>firebase.crashlytics</b>
         */
        public FirebaseCrashlyticsLibraryAccessors getCrashlytics() {
            return laccForFirebaseCrashlyticsLibraryAccessors;
        }

        /**
         * Group of libraries at <b>firebase.firestore</b>
         */
        public FirebaseFirestoreLibraryAccessors getFirestore() {
            return laccForFirebaseFirestoreLibraryAccessors;
        }

        /**
         * Group of libraries at <b>firebase.functions</b>
         */
        public FirebaseFunctionsLibraryAccessors getFunctions() {
            return laccForFirebaseFunctionsLibraryAccessors;
        }

        /**
         * Group of libraries at <b>firebase.messaging</b>
         */
        public FirebaseMessagingLibraryAccessors getMessaging() {
            return laccForFirebaseMessagingLibraryAccessors;
        }

    }

    public static class FirebaseAnalyticsLibraryAccessors extends SubDependencyFactory {

        public FirebaseAnalyticsLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>com.google.firebase:firebase-analytics-ktx</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("firebase.analytics.ktx");
        }

    }

    public static class FirebaseAuthLibraryAccessors extends SubDependencyFactory {

        public FirebaseAuthLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>com.google.firebase:firebase-auth-ktx</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("firebase.auth.ktx");
        }

    }

    public static class FirebaseBomLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public FirebaseBomLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>bom</b> with <b>com.google.firebase:firebase-bom</b> coordinates and
         * with version reference <b>firebase.bom</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> asProvider() {
            return create("firebase.bom");
        }

        /**
         * Dependency provider for <b>v3280</b> with <b>com.google.firebase:firebase-bom</b> coordinates and
         * with version reference <b>firebase.bom.version</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getV3280() {
            return create("firebase.bom.v3280");
        }

    }

    public static class FirebaseCrashlyticsLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public FirebaseCrashlyticsLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>crashlytics</b> with <b>com.google.firebase:firebase-crashlytics</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> asProvider() {
            return create("firebase.crashlytics");
        }

        /**
         * Dependency provider for <b>ktx</b> with <b>com.google.firebase:firebase-crashlytics-ktx</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("firebase.crashlytics.ktx");
        }

    }

    public static class FirebaseFirestoreLibraryAccessors extends SubDependencyFactory {

        public FirebaseFirestoreLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>com.google.firebase:firebase-firestore-ktx</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("firebase.firestore.ktx");
        }

    }

    public static class FirebaseFunctionsLibraryAccessors extends SubDependencyFactory {

        public FirebaseFunctionsLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>com.google.firebase:firebase-functions-ktx</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("firebase.functions.ktx");
        }

    }

    public static class FirebaseMessagingLibraryAccessors extends SubDependencyFactory {

        public FirebaseMessagingLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>com.google.firebase:firebase-messaging-ktx</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("firebase.messaging.ktx");
        }

    }

    public static class GoogleLibraryAccessors extends SubDependencyFactory {
        private final GoogleFirebaseLibraryAccessors laccForGoogleFirebaseLibraryAccessors = new GoogleFirebaseLibraryAccessors(owner);

        public GoogleLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>google.firebase</b>
         */
        public GoogleFirebaseLibraryAccessors getFirebase() {
            return laccForGoogleFirebaseLibraryAccessors;
        }

    }

    public static class GoogleFirebaseLibraryAccessors extends SubDependencyFactory {
        private final GoogleFirebaseAnalyticsLibraryAccessors laccForGoogleFirebaseAnalyticsLibraryAccessors = new GoogleFirebaseAnalyticsLibraryAccessors(owner);
        private final GoogleFirebaseAuthLibraryAccessors laccForGoogleFirebaseAuthLibraryAccessors = new GoogleFirebaseAuthLibraryAccessors(owner);
        private final GoogleFirebaseFirestoreLibraryAccessors laccForGoogleFirebaseFirestoreLibraryAccessors = new GoogleFirebaseFirestoreLibraryAccessors(owner);
        private final GoogleFirebaseFunctionsLibraryAccessors laccForGoogleFirebaseFunctionsLibraryAccessors = new GoogleFirebaseFunctionsLibraryAccessors(owner);
        private final GoogleFirebaseMessagingLibraryAccessors laccForGoogleFirebaseMessagingLibraryAccessors = new GoogleFirebaseMessagingLibraryAccessors(owner);

        public GoogleFirebaseLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>google.firebase.analytics</b>
         */
        public GoogleFirebaseAnalyticsLibraryAccessors getAnalytics() {
            return laccForGoogleFirebaseAnalyticsLibraryAccessors;
        }

        /**
         * Group of libraries at <b>google.firebase.auth</b>
         */
        public GoogleFirebaseAuthLibraryAccessors getAuth() {
            return laccForGoogleFirebaseAuthLibraryAccessors;
        }

        /**
         * Group of libraries at <b>google.firebase.firestore</b>
         */
        public GoogleFirebaseFirestoreLibraryAccessors getFirestore() {
            return laccForGoogleFirebaseFirestoreLibraryAccessors;
        }

        /**
         * Group of libraries at <b>google.firebase.functions</b>
         */
        public GoogleFirebaseFunctionsLibraryAccessors getFunctions() {
            return laccForGoogleFirebaseFunctionsLibraryAccessors;
        }

        /**
         * Group of libraries at <b>google.firebase.messaging</b>
         */
        public GoogleFirebaseMessagingLibraryAccessors getMessaging() {
            return laccForGoogleFirebaseMessagingLibraryAccessors;
        }

    }

    public static class GoogleFirebaseAnalyticsLibraryAccessors extends SubDependencyFactory {

        public GoogleFirebaseAnalyticsLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>com.google.firebase:firebase-analytics-ktx</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("google.firebase.analytics.ktx");
        }

    }

    public static class GoogleFirebaseAuthLibraryAccessors extends SubDependencyFactory {

        public GoogleFirebaseAuthLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>com.google.firebase:firebase-auth-ktx</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("google.firebase.auth.ktx");
        }

    }

    public static class GoogleFirebaseFirestoreLibraryAccessors extends SubDependencyFactory {

        public GoogleFirebaseFirestoreLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>com.google.firebase:firebase-firestore-ktx</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("google.firebase.firestore.ktx");
        }

    }

    public static class GoogleFirebaseFunctionsLibraryAccessors extends SubDependencyFactory {

        public GoogleFirebaseFunctionsLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>com.google.firebase:firebase-functions-ktx</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("google.firebase.functions.ktx");
        }

    }

    public static class GoogleFirebaseMessagingLibraryAccessors extends SubDependencyFactory {

        public GoogleFirebaseMessagingLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>ktx</b> with <b>com.google.firebase:firebase-messaging-ktx</b> coordinates and
         * with <b>no version specified</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getKtx() {
            return create("google.firebase.messaging.ktx");
        }

    }

    public static class HiltLibraryAccessors extends SubDependencyFactory {
        private final HiltAndroidLibraryAccessors laccForHiltAndroidLibraryAccessors = new HiltAndroidLibraryAccessors(owner);

        public HiltLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>hilt.android</b>
         */
        public HiltAndroidLibraryAccessors getAndroid() {
            return laccForHiltAndroidLibraryAccessors;
        }

    }

    public static class HiltAndroidLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public HiltAndroidLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>android</b> with <b>com.google.dagger:hilt-android</b> coordinates and
         * with version reference <b>hilt</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> asProvider() {
            return create("hilt.android");
        }

        /**
         * Dependency provider for <b>compiler</b> with <b>com.google.dagger:hilt-android-compiler</b> coordinates and
         * with version reference <b>hilt</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getCompiler() {
            return create("hilt.android.compiler");
        }

    }

    public static class IdentityLibraryAccessors extends SubDependencyFactory {

        public IdentityLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>jvm</b> with <b>com.android.identity:identity-jvm</b> coordinates and
         * with version reference <b>identity.jvm</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getJvm() {
            return create("identity.jvm");
        }

    }

    public static class KotlinxLibraryAccessors extends SubDependencyFactory {
        private final KotlinxCoroutinesLibraryAccessors laccForKotlinxCoroutinesLibraryAccessors = new KotlinxCoroutinesLibraryAccessors(owner);

        public KotlinxLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>kotlinx.coroutines</b>
         */
        public KotlinxCoroutinesLibraryAccessors getCoroutines() {
            return laccForKotlinxCoroutinesLibraryAccessors;
        }

    }

    public static class KotlinxCoroutinesLibraryAccessors extends SubDependencyFactory {
        private final KotlinxCoroutinesPlayLibraryAccessors laccForKotlinxCoroutinesPlayLibraryAccessors = new KotlinxCoroutinesPlayLibraryAccessors(owner);

        public KotlinxCoroutinesLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>android</b> with <b>org.jetbrains.kotlinx:kotlinx-coroutines-android</b> coordinates and
         * with version reference <b>kotlinx.coroutines.android</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getAndroid() {
            return create("kotlinx.coroutines.android");
        }

        /**
         * Dependency provider for <b>test</b> with <b>org.jetbrains.kotlinx:kotlinx-coroutines-test</b> coordinates and
         * with version reference <b>kotlinx.coroutines.test</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getTest() {
            return create("kotlinx.coroutines.test");
        }

        /**
         * Group of libraries at <b>kotlinx.coroutines.play</b>
         */
        public KotlinxCoroutinesPlayLibraryAccessors getPlay() {
            return laccForKotlinxCoroutinesPlayLibraryAccessors;
        }

    }

    public static class KotlinxCoroutinesPlayLibraryAccessors extends SubDependencyFactory {

        public KotlinxCoroutinesPlayLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>services</b> with <b>org.jetbrains.kotlinx:kotlinx-coroutines-play-services</b> coordinates and
         * with version reference <b>kotlinx.coroutines.play.services</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getServices() {
            return create("kotlinx.coroutines.play.services");
        }

    }

    public static class LoggingLibraryAccessors extends SubDependencyFactory {

        public LoggingLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>interceptor</b> with <b>com.squareup.okhttp3:logging-interceptor</b> coordinates and
         * with version reference <b>logging.interceptor</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getInterceptor() {
            return create("logging.interceptor");
        }

    }

    public static class MockitoLibraryAccessors extends SubDependencyFactory {

        public MockitoLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>core</b> with <b>org.mockito:mockito-core</b> coordinates and
         * with version reference <b>mockito.core</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getCore() {
            return create("mockito.core");
        }

    }

    public static class PlayLibraryAccessors extends SubDependencyFactory {
        private final PlayServicesLibraryAccessors laccForPlayServicesLibraryAccessors = new PlayServicesLibraryAccessors(owner);

        public PlayLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Group of libraries at <b>play.services</b>
         */
        public PlayServicesLibraryAccessors getServices() {
            return laccForPlayServicesLibraryAccessors;
        }

    }

    public static class PlayServicesLibraryAccessors extends SubDependencyFactory {

        public PlayServicesLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

        /**
         * Dependency provider for <b>auth</b> with <b>com.google.android.gms:play-services-auth</b> coordinates and
         * with version reference <b>play.services.auth</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getAuth() {
            return create("play.services.auth");
        }

        /**
         * Dependency provider for <b>base</b> with <b>com.google.android.gms:play-services-base</b> coordinates and
         * with version reference <b>play.services.base</b>
         * <p>
         * This dependency was declared in catalog libs.versions.toml
         */
        public Provider<MinimalExternalModuleDependency> getBase() {
            return create("play.services.base");
        }

    }

    public static class VersionAccessors extends VersionFactory  {

        private final AccompanistVersionAccessors vaccForAccompanistVersionAccessors = new AccompanistVersionAccessors(providers, config);
        private final ActivityVersionAccessors vaccForActivityVersionAccessors = new ActivityVersionAccessors(providers, config);
        private final AndroidxVersionAccessors vaccForAndroidxVersionAccessors = new AndroidxVersionAccessors(providers, config);
        private final AnimationVersionAccessors vaccForAnimationVersionAccessors = new AnimationVersionAccessors(providers, config);
        private final ComposeVersionAccessors vaccForComposeVersionAccessors = new ComposeVersionAccessors(providers, config);
        private final ConverterVersionAccessors vaccForConverterVersionAccessors = new ConverterVersionAccessors(providers, config);
        private final CoreVersionAccessors vaccForCoreVersionAccessors = new CoreVersionAccessors(providers, config);
        private final DatastoreVersionAccessors vaccForDatastoreVersionAccessors = new DatastoreVersionAccessors(providers, config);
        private final EspressoVersionAccessors vaccForEspressoVersionAccessors = new EspressoVersionAccessors(providers, config);
        private final FirebaseVersionAccessors vaccForFirebaseVersionAccessors = new FirebaseVersionAccessors(providers, config);
        private final HiltVersionAccessors vaccForHiltVersionAccessors = new HiltVersionAccessors(providers, config);
        private final IdentityVersionAccessors vaccForIdentityVersionAccessors = new IdentityVersionAccessors(providers, config);
        private final KotlinxVersionAccessors vaccForKotlinxVersionAccessors = new KotlinxVersionAccessors(providers, config);
        private final LifecycleVersionAccessors vaccForLifecycleVersionAccessors = new LifecycleVersionAccessors(providers, config);
        private final LoggingVersionAccessors vaccForLoggingVersionAccessors = new LoggingVersionAccessors(providers, config);
        private final MockitoVersionAccessors vaccForMockitoVersionAccessors = new MockitoVersionAccessors(providers, config);
        private final NavigationVersionAccessors vaccForNavigationVersionAccessors = new NavigationVersionAccessors(providers, config);
        private final PagingVersionAccessors vaccForPagingVersionAccessors = new PagingVersionAccessors(providers, config);
        private final PlayVersionAccessors vaccForPlayVersionAccessors = new PlayVersionAccessors(providers, config);
        private final RoomVersionAccessors vaccForRoomVersionAccessors = new RoomVersionAccessors(providers, config);
        private final RuntimeVersionAccessors vaccForRuntimeVersionAccessors = new RuntimeVersionAccessors(providers, config);
        private final UiVersionAccessors vaccForUiVersionAccessors = new UiVersionAccessors(providers, config);
        private final WorkVersionAccessors vaccForWorkVersionAccessors = new WorkVersionAccessors(providers, config);
        public VersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>agp</b> with value <b>8.2.2</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getAgp() { return getVersion("agp"); }

        /**
         * Version alias <b>calendar</b> with value <b>1.2.0</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getCalendar() { return getVersion("calendar"); }

        /**
         * Version alias <b>junit</b> with value <b>4.13.2</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getJunit() { return getVersion("junit"); }

        /**
         * Version alias <b>kotlin</b> with value <b>1.9.22</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getKotlin() { return getVersion("kotlin"); }

        /**
         * Version alias <b>material3</b> with value <b>1.2.0</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getMaterial3() { return getVersion("material3"); }

        /**
         * Version alias <b>multidex</b> with value <b>2.0.1</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getMultidex() { return getVersion("multidex"); }

        /**
         * Version alias <b>okhttp</b> with value <b>4.12.0</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getOkhttp() { return getVersion("okhttp"); }

        /**
         * Group of versions at <b>versions.accompanist</b>
         */
        public AccompanistVersionAccessors getAccompanist() {
            return vaccForAccompanistVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.activity</b>
         */
        public ActivityVersionAccessors getActivity() {
            return vaccForActivityVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.androidx</b>
         */
        public AndroidxVersionAccessors getAndroidx() {
            return vaccForAndroidxVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.animation</b>
         */
        public AnimationVersionAccessors getAnimation() {
            return vaccForAnimationVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.compose</b>
         */
        public ComposeVersionAccessors getCompose() {
            return vaccForComposeVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.converter</b>
         */
        public ConverterVersionAccessors getConverter() {
            return vaccForConverterVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.core</b>
         */
        public CoreVersionAccessors getCore() {
            return vaccForCoreVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.datastore</b>
         */
        public DatastoreVersionAccessors getDatastore() {
            return vaccForDatastoreVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.espresso</b>
         */
        public EspressoVersionAccessors getEspresso() {
            return vaccForEspressoVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.firebase</b>
         */
        public FirebaseVersionAccessors getFirebase() {
            return vaccForFirebaseVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.hilt</b>
         */
        public HiltVersionAccessors getHilt() {
            return vaccForHiltVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.identity</b>
         */
        public IdentityVersionAccessors getIdentity() {
            return vaccForIdentityVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.kotlinx</b>
         */
        public KotlinxVersionAccessors getKotlinx() {
            return vaccForKotlinxVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.lifecycle</b>
         */
        public LifecycleVersionAccessors getLifecycle() {
            return vaccForLifecycleVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.logging</b>
         */
        public LoggingVersionAccessors getLogging() {
            return vaccForLoggingVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.mockito</b>
         */
        public MockitoVersionAccessors getMockito() {
            return vaccForMockitoVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.navigation</b>
         */
        public NavigationVersionAccessors getNavigation() {
            return vaccForNavigationVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.paging</b>
         */
        public PagingVersionAccessors getPaging() {
            return vaccForPagingVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.play</b>
         */
        public PlayVersionAccessors getPlay() {
            return vaccForPlayVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.room</b>
         */
        public RoomVersionAccessors getRoom() {
            return vaccForRoomVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.runtime</b>
         */
        public RuntimeVersionAccessors getRuntime() {
            return vaccForRuntimeVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.ui</b>
         */
        public UiVersionAccessors getUi() {
            return vaccForUiVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.work</b>
         */
        public WorkVersionAccessors getWork() {
            return vaccForWorkVersionAccessors;
        }

    }

    public static class AccompanistVersionAccessors extends VersionFactory  {

        private final AccompanistNavigationVersionAccessors vaccForAccompanistNavigationVersionAccessors = new AccompanistNavigationVersionAccessors(providers, config);
        public AccompanistVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Group of versions at <b>versions.accompanist.navigation</b>
         */
        public AccompanistNavigationVersionAccessors getNavigation() {
            return vaccForAccompanistNavigationVersionAccessors;
        }

    }

    public static class AccompanistNavigationVersionAccessors extends VersionFactory  {

        public AccompanistNavigationVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>accompanist.navigation.animation</b> with value <b>0.30.1</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getAnimation() { return getVersion("accompanist.navigation.animation"); }

    }

    public static class ActivityVersionAccessors extends VersionFactory  {

        public ActivityVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>activity.compose</b> with value <b>1.8.2</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getCompose() { return getVersion("activity.compose"); }

        /**
         * Version alias <b>activity.ktx</b> with value <b>1.9.2</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getKtx() { return getVersion("activity.ktx"); }

    }

    public static class AndroidxVersionAccessors extends VersionFactory  {

        private final AndroidxActivityVersionAccessors vaccForAndroidxActivityVersionAccessors = new AndroidxActivityVersionAccessors(providers, config);
        private final AndroidxLifecycleVersionAccessors vaccForAndroidxLifecycleVersionAccessors = new AndroidxLifecycleVersionAccessors(providers, config);
        private final AndroidxTestVersionAccessors vaccForAndroidxTestVersionAccessors = new AndroidxTestVersionAccessors(providers, config);
        public AndroidxVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Group of versions at <b>versions.androidx.activity</b>
         */
        public AndroidxActivityVersionAccessors getActivity() {
            return vaccForAndroidxActivityVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.androidx.lifecycle</b>
         */
        public AndroidxLifecycleVersionAccessors getLifecycle() {
            return vaccForAndroidxLifecycleVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.androidx.test</b>
         */
        public AndroidxTestVersionAccessors getTest() {
            return vaccForAndroidxTestVersionAccessors;
        }

    }

    public static class AndroidxActivityVersionAccessors extends VersionFactory  {

        public AndroidxActivityVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>androidx.activity.compose</b> with value <b>1.9.1</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getCompose() { return getVersion("androidx.activity.compose"); }

    }

    public static class AndroidxLifecycleVersionAccessors extends VersionFactory  {

        private final AndroidxLifecycleViewmodelVersionAccessors vaccForAndroidxLifecycleViewmodelVersionAccessors = new AndroidxLifecycleViewmodelVersionAccessors(providers, config);
        public AndroidxLifecycleVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Group of versions at <b>versions.androidx.lifecycle.viewmodel</b>
         */
        public AndroidxLifecycleViewmodelVersionAccessors getViewmodel() {
            return vaccForAndroidxLifecycleViewmodelVersionAccessors;
        }

    }

    public static class AndroidxLifecycleViewmodelVersionAccessors extends VersionFactory  {

        public AndroidxLifecycleViewmodelVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>androidx.lifecycle.viewmodel.compose</b> with value <b>2.8.4</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getCompose() { return getVersion("androidx.lifecycle.viewmodel.compose"); }

    }

    public static class AndroidxTestVersionAccessors extends VersionFactory  {

        private final AndroidxTestExtVersionAccessors vaccForAndroidxTestExtVersionAccessors = new AndroidxTestExtVersionAccessors(providers, config);
        public AndroidxTestVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Group of versions at <b>versions.androidx.test.ext</b>
         */
        public AndroidxTestExtVersionAccessors getExt() {
            return vaccForAndroidxTestExtVersionAccessors;
        }

    }

    public static class AndroidxTestExtVersionAccessors extends VersionFactory  {

        public AndroidxTestExtVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>androidx.test.ext.junit</b> with value <b>1.1.5</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getJunit() { return getVersion("androidx.test.ext.junit"); }

    }

    public static class AnimationVersionAccessors extends VersionFactory  implements VersionNotationSupplier {

        public AnimationVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>animation</b> with value <b>1.5.4</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> asProvider() { return getVersion("animation"); }

        /**
         * Version alias <b>animation.graphics</b> with value <b>1.6.8</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getGraphics() { return getVersion("animation.graphics"); }

    }

    public static class ComposeVersionAccessors extends VersionFactory  {

        public ComposeVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>compose.bom</b> with value <b>2024.01.00</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in settings file 'settings.gradle.kts'
         */
        public Provider<String> getBom() { return getVersion("compose.bom"); }

        /**
         * Version alias <b>compose.compiler</b> with value <b>1.5.8</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in settings file 'settings.gradle.kts'
         */
        public Provider<String> getCompiler() { return getVersion("compose.compiler"); }

    }

    public static class ConverterVersionAccessors extends VersionFactory  {

        public ConverterVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>converter.gson</b> with value <b>2.9.0</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getGson() { return getVersion("converter.gson"); }

    }

    public static class CoreVersionAccessors extends VersionFactory  implements VersionNotationSupplier {

        public CoreVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>core</b> with value <b>1.2.0</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> asProvider() { return getVersion("core"); }

        /**
         * Version alias <b>core.ktx</b> with value <b>1.12.0</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getKtx() { return getVersion("core.ktx"); }

    }

    public static class DatastoreVersionAccessors extends VersionFactory  {

        private final DatastorePreferencesVersionAccessors vaccForDatastorePreferencesVersionAccessors = new DatastorePreferencesVersionAccessors(providers, config);
        public DatastoreVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Group of versions at <b>versions.datastore.preferences</b>
         */
        public DatastorePreferencesVersionAccessors getPreferences() {
            return vaccForDatastorePreferencesVersionAccessors;
        }

    }

    public static class DatastorePreferencesVersionAccessors extends VersionFactory  {

        public DatastorePreferencesVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>datastore.preferences.core</b> with value <b>1.1.1</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getCore() { return getVersion("datastore.preferences.core"); }

    }

    public static class EspressoVersionAccessors extends VersionFactory  {

        public EspressoVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>espresso.core</b> with value <b>3.5.1</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getCore() { return getVersion("espresso.core"); }

    }

    public static class FirebaseVersionAccessors extends VersionFactory  {

        private final FirebaseBomVersionAccessors vaccForFirebaseBomVersionAccessors = new FirebaseBomVersionAccessors(providers, config);
        public FirebaseVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Group of versions at <b>versions.firebase.bom</b>
         */
        public FirebaseBomVersionAccessors getBom() {
            return vaccForFirebaseBomVersionAccessors;
        }

    }

    public static class FirebaseBomVersionAccessors extends VersionFactory  implements VersionNotationSupplier {

        public FirebaseBomVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>firebase.bom</b> with value <b>32.7.2</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> asProvider() { return getVersion("firebase.bom"); }

        /**
         * Version alias <b>firebase.bom.version</b> with value <b>32.8.0</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getVersion() { return getVersion("firebase.bom.version"); }

    }

    public static class HiltVersionAccessors extends VersionFactory  implements VersionNotationSupplier {

        private final HiltNavigationVersionAccessors vaccForHiltNavigationVersionAccessors = new HiltNavigationVersionAccessors(providers, config);
        public HiltVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>hilt</b> with value <b>2.50</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> asProvider() { return getVersion("hilt"); }

        /**
         * Group of versions at <b>versions.hilt.navigation</b>
         */
        public HiltNavigationVersionAccessors getNavigation() {
            return vaccForHiltNavigationVersionAccessors;
        }

    }

    public static class HiltNavigationVersionAccessors extends VersionFactory  {

        public HiltNavigationVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>hilt.navigation.compose</b> with value <b>1.2.0</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getCompose() { return getVersion("hilt.navigation.compose"); }

    }

    public static class IdentityVersionAccessors extends VersionFactory  {

        public IdentityVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>identity.jvm</b> with value <b>202411.1</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getJvm() { return getVersion("identity.jvm"); }

    }

    public static class KotlinxVersionAccessors extends VersionFactory  {

        private final KotlinxCoroutinesVersionAccessors vaccForKotlinxCoroutinesVersionAccessors = new KotlinxCoroutinesVersionAccessors(providers, config);
        public KotlinxVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Group of versions at <b>versions.kotlinx.coroutines</b>
         */
        public KotlinxCoroutinesVersionAccessors getCoroutines() {
            return vaccForKotlinxCoroutinesVersionAccessors;
        }

    }

    public static class KotlinxCoroutinesVersionAccessors extends VersionFactory  {

        private final KotlinxCoroutinesPlayVersionAccessors vaccForKotlinxCoroutinesPlayVersionAccessors = new KotlinxCoroutinesPlayVersionAccessors(providers, config);
        public KotlinxCoroutinesVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>kotlinx.coroutines.android</b> with value <b>1.7.3</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getAndroid() { return getVersion("kotlinx.coroutines.android"); }

        /**
         * Version alias <b>kotlinx.coroutines.test</b> with value <b>1.7.3</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getTest() { return getVersion("kotlinx.coroutines.test"); }

        /**
         * Group of versions at <b>versions.kotlinx.coroutines.play</b>
         */
        public KotlinxCoroutinesPlayVersionAccessors getPlay() {
            return vaccForKotlinxCoroutinesPlayVersionAccessors;
        }

    }

    public static class KotlinxCoroutinesPlayVersionAccessors extends VersionFactory  {

        public KotlinxCoroutinesPlayVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>kotlinx.coroutines.play.services</b> with value <b>1.7.3</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getServices() { return getVersion("kotlinx.coroutines.play.services"); }

    }

    public static class LifecycleVersionAccessors extends VersionFactory  {

        private final LifecycleRuntimeVersionAccessors vaccForLifecycleRuntimeVersionAccessors = new LifecycleRuntimeVersionAccessors(providers, config);
        private final LifecycleViewmodelVersionAccessors vaccForLifecycleViewmodelVersionAccessors = new LifecycleViewmodelVersionAccessors(providers, config);
        public LifecycleVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Group of versions at <b>versions.lifecycle.runtime</b>
         */
        public LifecycleRuntimeVersionAccessors getRuntime() {
            return vaccForLifecycleRuntimeVersionAccessors;
        }

        /**
         * Group of versions at <b>versions.lifecycle.viewmodel</b>
         */
        public LifecycleViewmodelVersionAccessors getViewmodel() {
            return vaccForLifecycleViewmodelVersionAccessors;
        }

    }

    public static class LifecycleRuntimeVersionAccessors extends VersionFactory  {

        private final LifecycleRuntimeKtxVersionAccessors vaccForLifecycleRuntimeKtxVersionAccessors = new LifecycleRuntimeKtxVersionAccessors(providers, config);
        public LifecycleRuntimeVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Group of versions at <b>versions.lifecycle.runtime.ktx</b>
         */
        public LifecycleRuntimeKtxVersionAccessors getKtx() {
            return vaccForLifecycleRuntimeKtxVersionAccessors;
        }

    }

    public static class LifecycleRuntimeKtxVersionAccessors extends VersionFactory  implements VersionNotationSupplier {

        public LifecycleRuntimeKtxVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>lifecycle.runtime.ktx</b> with value <b>2.7.0</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> asProvider() { return getVersion("lifecycle.runtime.ktx"); }

        /**
         * Version alias <b>lifecycle.runtime.ktx.version</b> with value <b>2.8.6</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getVersion() { return getVersion("lifecycle.runtime.ktx.version"); }

    }

    public static class LifecycleViewmodelVersionAccessors extends VersionFactory  {

        public LifecycleViewmodelVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>lifecycle.viewmodel.compose</b> with value <b>2.7.0</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getCompose() { return getVersion("lifecycle.viewmodel.compose"); }

    }

    public static class LoggingVersionAccessors extends VersionFactory  {

        public LoggingVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>logging.interceptor</b> with value <b>4.12.0</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getInterceptor() { return getVersion("logging.interceptor"); }

    }

    public static class MockitoVersionAccessors extends VersionFactory  {

        public MockitoVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>mockito.core</b> with value <b>5.7.0</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getCore() { return getVersion("mockito.core"); }

    }

    public static class NavigationVersionAccessors extends VersionFactory  {

        private final NavigationComposeVersionAccessors vaccForNavigationComposeVersionAccessors = new NavigationComposeVersionAccessors(providers, config);
        public NavigationVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Group of versions at <b>versions.navigation.compose</b>
         */
        public NavigationComposeVersionAccessors getCompose() {
            return vaccForNavigationComposeVersionAccessors;
        }

    }

    public static class NavigationComposeVersionAccessors extends VersionFactory  implements VersionNotationSupplier {

        public NavigationComposeVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>navigation.compose</b> with value <b>2.7.6</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> asProvider() { return getVersion("navigation.compose"); }

        /**
         * Version alias <b>navigation.compose.version</b> with value <b>2.8.1</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getVersion() { return getVersion("navigation.compose.version"); }

    }

    public static class PagingVersionAccessors extends VersionFactory  {

        public PagingVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>paging.compose</b> with value <b>3.3.2</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getCompose() { return getVersion("paging.compose"); }

    }

    public static class PlayVersionAccessors extends VersionFactory  {

        private final PlayServicesVersionAccessors vaccForPlayServicesVersionAccessors = new PlayServicesVersionAccessors(providers, config);
        public PlayVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Group of versions at <b>versions.play.services</b>
         */
        public PlayServicesVersionAccessors getServices() {
            return vaccForPlayServicesVersionAccessors;
        }

    }

    public static class PlayServicesVersionAccessors extends VersionFactory  {

        public PlayServicesVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>play.services.auth</b> with value <b>21.2.0</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getAuth() { return getVersion("play.services.auth"); }

        /**
         * Version alias <b>play.services.base</b> with value <b>18.5.0</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getBase() { return getVersion("play.services.base"); }

    }

    public static class RoomVersionAccessors extends VersionFactory  {

        public RoomVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>room.ktx</b> with value <b>2.6.1</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getKtx() { return getVersion("room.ktx"); }

    }

    public static class RuntimeVersionAccessors extends VersionFactory  {

        public RuntimeVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>runtime.livedata</b> with value <b>1.6.8</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getLivedata() { return getVersion("runtime.livedata"); }

    }

    public static class UiVersionAccessors extends VersionFactory  {

        public UiVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>ui.util</b> with value <b>1.6.8</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getUtil() { return getVersion("ui.util"); }

    }

    public static class WorkVersionAccessors extends VersionFactory  {

        private final WorkRuntimeVersionAccessors vaccForWorkRuntimeVersionAccessors = new WorkRuntimeVersionAccessors(providers, config);
        public WorkVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Group of versions at <b>versions.work.runtime</b>
         */
        public WorkRuntimeVersionAccessors getRuntime() {
            return vaccForWorkRuntimeVersionAccessors;
        }

    }

    public static class WorkRuntimeVersionAccessors extends VersionFactory  {

        public WorkRuntimeVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Version alias <b>work.runtime.ktx</b> with value <b>2.9.1</b>
         * <p>
         * If the version is a rich version and cannot be represented as a
         * single version string, an empty string is returned.
         * <p>
         * This version was declared in catalog libs.versions.toml
         */
        public Provider<String> getKtx() { return getVersion("work.runtime.ktx"); }

    }

    public static class BundleAccessors extends BundleFactory {

        public BundleAccessors(ObjectFactory objects, ProviderFactory providers, DefaultVersionCatalog config, ImmutableAttributesFactory attributesFactory, CapabilityNotationParser capabilityNotationParser) { super(objects, providers, config, attributesFactory, capabilityNotationParser); }

    }

    public static class PluginAccessors extends PluginFactory {
        private final AndroidPluginAccessors paccForAndroidPluginAccessors = new AndroidPluginAccessors(providers, config);
        private final GooglePluginAccessors paccForGooglePluginAccessors = new GooglePluginAccessors(providers, config);
        private final HiltPluginAccessors paccForHiltPluginAccessors = new HiltPluginAccessors(providers, config);
        private final JetbrainsPluginAccessors paccForJetbrainsPluginAccessors = new JetbrainsPluginAccessors(providers, config);
        private final KotlinPluginAccessors paccForKotlinPluginAccessors = new KotlinPluginAccessors(providers, config);

        public PluginAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Group of plugins at <b>plugins.android</b>
         */
        public AndroidPluginAccessors getAndroid() {
            return paccForAndroidPluginAccessors;
        }

        /**
         * Group of plugins at <b>plugins.google</b>
         */
        public GooglePluginAccessors getGoogle() {
            return paccForGooglePluginAccessors;
        }

        /**
         * Group of plugins at <b>plugins.hilt</b>
         */
        public HiltPluginAccessors getHilt() {
            return paccForHiltPluginAccessors;
        }

        /**
         * Group of plugins at <b>plugins.jetbrains</b>
         */
        public JetbrainsPluginAccessors getJetbrains() {
            return paccForJetbrainsPluginAccessors;
        }

        /**
         * Group of plugins at <b>plugins.kotlin</b>
         */
        public KotlinPluginAccessors getKotlin() {
            return paccForKotlinPluginAccessors;
        }

    }

    public static class AndroidPluginAccessors extends PluginFactory {

        public AndroidPluginAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Plugin provider for <b>android.application</b> with plugin id <b>com.android.application</b> and
         * with version reference <b>agp</b>
         * <p>
         * This plugin was declared in catalog libs.versions.toml
         */
        public Provider<PluginDependency> getApplication() { return createPlugin("android.application"); }

    }

    public static class GooglePluginAccessors extends PluginFactory {

        public GooglePluginAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Plugin provider for <b>google.services</b> with plugin id <b>com.google.gms.google-services</b> and
         * with version <b>4.4.2</b>
         * <p>
         * This plugin was declared in catalog libs.versions.toml
         */
        public Provider<PluginDependency> getServices() { return createPlugin("google.services"); }

    }

    public static class HiltPluginAccessors extends PluginFactory {

        public HiltPluginAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Plugin provider for <b>hilt.android</b> with plugin id <b>com.google.dagger.hilt.android</b> and
         * with version reference <b>hilt</b>
         * <p>
         * This plugin was declared in catalog libs.versions.toml
         */
        public Provider<PluginDependency> getAndroid() { return createPlugin("hilt.android"); }

    }

    public static class JetbrainsPluginAccessors extends PluginFactory {
        private final JetbrainsKotlinPluginAccessors paccForJetbrainsKotlinPluginAccessors = new JetbrainsKotlinPluginAccessors(providers, config);

        public JetbrainsPluginAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Group of plugins at <b>plugins.jetbrains.kotlin</b>
         */
        public JetbrainsKotlinPluginAccessors getKotlin() {
            return paccForJetbrainsKotlinPluginAccessors;
        }

    }

    public static class JetbrainsKotlinPluginAccessors extends PluginFactory {

        public JetbrainsKotlinPluginAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Plugin provider for <b>jetbrains.kotlin.android</b> with plugin id <b>org.jetbrains.kotlin.android</b> and
         * with version reference <b>kotlin</b>
         * <p>
         * This plugin was declared in catalog libs.versions.toml
         */
        public Provider<PluginDependency> getAndroid() { return createPlugin("jetbrains.kotlin.android"); }

    }

    public static class KotlinPluginAccessors extends PluginFactory {

        public KotlinPluginAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

        /**
         * Plugin provider for <b>kotlin.android</b> with plugin id <b>org.jetbrains.kotlin.android</b> and
         * with version <b>1.9.22</b>
         * <p>
         * This plugin was declared in settings file 'settings.gradle.kts'
         */
        public Provider<PluginDependency> getAndroid() { return createPlugin("kotlin.android"); }

    }

}
