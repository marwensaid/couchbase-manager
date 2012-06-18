/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package es.rickyepoderi.couchbasemanager.web;

import com.sun.enterprise.container.common.spi.util.JavaEEIOUtils;
import com.sun.enterprise.deployment.runtime.web.ManagerProperties;
import com.sun.enterprise.deployment.runtime.web.SessionManager;
import com.sun.enterprise.deployment.runtime.web.WebProperty;
import com.sun.enterprise.web.BasePersistenceStrategyBuilder;
import com.sun.enterprise.web.ServerConfigLookup;
import es.rickyepoderi.couchbasemanager.couchbase.transcoders.AppSerializingTranscoder;
import es.rickyepoderi.couchbasemanager.session.CouchbaseManager;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jvnet.hk2.annotations.Inject;
import org.apache.catalina.Context;
import org.apache.catalina.core.StandardContext;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.PerLookup;

/**
 *
 * <p>Class that builds the manager and establish it into the context of
 * an application. The documentation is <a href="http://docs.oracle.com/cd/E18930_01/html/821-2415/gkmhr.html">
 * here</a>. Besides the manager has to be defined inside in 
 * META-INF/inhabitants/default as the manager with the next line:</p>
 * 
 * <pre>
 * class=com.sun.enterprise.web.MemoryStrategyBuilder,index=com.sun.enterprise.web.PersistenceStrategyBuilder:memory
 * </pre>
 * 
 * <p>Besides the service needs to be called "coherence-web", if other 
 * name is used it is not loaded (nasty checks inside glassfish code prevent
 * the manager to be actually loaded).See 
 * <a href="http://java.net/projects/glassfish/sources/svn/content/trunk/main/appserver/web/web-glue/src/main/java/com/sun/enterprise/web/SessionManagerConfigurationHelper.java">
 * SessionManagerConfigurationHelper</a> which performs some checks and
 * avoid using other custom names (even the CUSTOM).</p>
 * 
 * <p>The builder can be customized with some properties inside the 
 * the glassfish-web.xml deployment descriptor. The following properties
 * can be used right now:</p>
 * 
 * <ul>
 *   <li>repositoryUrl: The list of URIs of the couchbase servers, it is a
 *       comma separated list. For example "http://server1:8091/pools,http://server2:8091/pools".
 *       Default: "http://localhost:8091/pools".</li>
 *   <li>repositoryBucket: Repository bucket to use in couchbase. Default: "default".</li>
 *   <li>repositoryUsername: Repository admin username to use in the bucket. Default no user.</li>
 *   <li>repositoryPassword: Repository admin password. Default no password.</li>
 *   <li>sticky: Change the manager to be sticky. Sticky works very different,
 *       session is not locked in couchbase and it is not reload every request.
 *       Default false.</li>
 *   <li>lockTime: The amount of time in seconds that a key will be locked 
 *       inside couchbase manager without being released. In current couchbase 
 *       implementation the maximum locktime is 30 seconds. Default 30.</li>
 *   <li>maxTimeNotSaving: The maximum amount of time in minutes that a 
 *       session can only be refreshed without a real saving in couchbase.
 *       when only accessed a session is touched in couchbase, therefore 
 *       internal timestamps are not saved. This property sets a maximum
 *       time, in order to force a save. Default 5.</li>
 *   <li>operationTimeout: The time in milliseconds to wait for any operation
 *       against couchbase to timeout. Default 30000ms (30s).</li>
 * </ul>
 * 
 * <p>Example of configuration:</p>
 * 
 * <pre>
 * &lt;session-manager persistence-type="coherence-web"&gt;
 *   &lt;manager-properties&gt;
 *     &lt;property name="reapIntervalSeconds" value="20"/&gt;
 *     &lt;property name="repositoryUrl" value="http://localhost:8091/pools"/&gt;
 *   &lt;/manager-properties&gt;
 * &lt;/session-manager&gt;
 * </pre>
 * 
 * @author ricky
 */
@Service(name = "coherence-web")
@Scoped(PerLookup.class)
public class CouchbaseManagerStrategyBuilder extends BasePersistenceStrategyBuilder {

    /**
     * logger for the class
     */
    protected static final Logger log = Logger.getLogger(CouchbaseManagerStrategyBuilder.class.getName());
    
    //
    // PROPERTY NAMES
    //
    
    /**
     * Property to set the list (comma separated) of URIs to couchbase.
     */
    public static final String PROP_REPOSITORY_URL = "repositoryUrl";
    
    /**
     * Property to set the bucket to use.
     */
    public static final String PROP_REPOSITORY_BUCKET = "repositoryBucket";
    
    /**
     * Property to set the name of the user in couchbase.
     */
    public static final String PROP_REPOSITORY_USERNAME = "repositoryUsername";
    
    /**
     * Property to set the password of the user.
     */
    public static final String PROP_REPOSITORY_PASSWORD = "repositoryPassword";
    
    /**
     * Property to set the stickyness of the setup.
     */
    public static final String PROP_STICKY = "sticky";
    
    /**
     * Property that handles the amount of time in seconds to lock a key
     * inside getAndLock method (couchbase time has to be less or equal to 30s).
     */
    public static final String PROP_LOCK_TIME = "lockTime";
    
    /**
     * Property that handles the amount of time a sessions could be refreshed
     * (touched in couchbase) without saving. In normal manager configuration
     * the session is only touched when no modifications are performed in the
     * attributes of the session. That means the session timestamps are not
     * updated inside couchbase. This property assures a saving if session
     * was not updated in this time.
     */
    public static final String PROP_MAX_ACCESS_TIME_NOT_SAVING = "maxTimeNotSaving";
    
    /**
     * Property that manages the timeout for any couchbase operation. This 
     * timeout is managed in milliseconds.
     */
    public static final String PROP_OPERATION_TIMEOUT = "operationTimeout";
    
    //
    // DEFAULT VALUES FOR PROPERTIES
    //
    
    /**
     * Default value for repositoryUrl property.
     */
    protected static final String DEFAULT_REPOSITORY_URL = "http://localhost:8091/pools";
    
    /**
     * Default value for repositoryBucket property.
     */
    protected static final String DEFAULT_REPOSITORY_BUCKET = "default";
   
    /**
     * Default value for repositoryUsername property.
     */
    protected static final String DEFAULT_REPOSITORY_USERNAME = null;
    
    /**
     * Default value for repositoryPassword property.
     */
    protected static final String DEFAULT_REPOSITORY_PASSWORD = "";
    
    /**
     * Default value for stickyness (false).
     */
    protected static final boolean DEFAULT_STICKY = false;
    
    /**
     * Default lock time (maximum in couchbase 30s).
     */
    protected static final int DEFAULT_LOCK_TIME = 30;
    
    /**
     * Default max time without saving in couchbase (5 minutes).
     */
    protected static final int DEFAULT_MAX_ACCESS_TIME_NOT_SAVING = 5;
    
    /**
     * Default operation timeout (30000ms = 30s).
     */
    protected static final long DEFAULT_OPERATION_TIMEOUT = 30000;
    
    //
    // REAL PROPERTIES
    //
    
    /**
     * property for URL.
     */
    protected String repositoryUrl = DEFAULT_REPOSITORY_URL;
    
    /**
     * property for bucket name.
     */
    protected String repositoryBucket = DEFAULT_REPOSITORY_BUCKET;
    
    /**
     * property for username.
     */
    protected String repositoryUsername = DEFAULT_REPOSITORY_USERNAME;
    
    /**
     * property for password.
     */
    protected String repositoryPassword = DEFAULT_REPOSITORY_PASSWORD;
    
    /**
     * property for stickyness.
     */
    protected boolean sticky = DEFAULT_STICKY;
    
    /**
     * property to manage the lock time.
     */
    protected int lockTime = DEFAULT_LOCK_TIME;
    
    /**
     * property to manage the max time without saving in couchbase.
     */
    protected int maxAccessTimeNotSaving = DEFAULT_MAX_ACCESS_TIME_NOT_SAVING;
    
    /**
     * property to control the operation timeout in couchbase calls.
     */
    protected long operationTimeout = DEFAULT_OPERATION_TIMEOUT;
    
    /**
     * The ioUtils from glassfish package that are going to be used for getting
     * the Object input/output streams. It is used in the AppSerializingTranscoder.
     */
    @Inject
    private JavaEEIOUtils ioUtils;

    /**
     * Main method that creates the manager and sets it to the context of the 
     * application. The properties are read from the glassfish-web.xml and
     * the manager is created and assign to the context.
     * 
     * @param ctx The context to assign the manager
     * @param smBean The bean where properties are read
     * @param serverConfigLookup Configuration lookup
     */
    @Override
    public void initializePersistenceStrategy(Context ctx,
            SessionManager smBean, ServerConfigLookup serverConfigLookup) {
        log.fine("MemManagerStrategyBuilder.initializePersistenceStrategy: init");
        super.initializePersistenceStrategy(ctx, smBean, serverConfigLookup);
        // create the memory manager
        CouchbaseManager manager = new CouchbaseManager(repositoryUrl);
        // set values read from the configuration
        manager.setBucket(repositoryBucket);
        manager.setUsername(repositoryUsername);
        manager.setPassword(repositoryPassword);
        manager.setSticky(sticky);
        manager.setLockTime(lockTime);
        manager.setOperationTimeout(operationTimeout);
        log.log(Level.FINE, "MemManagerStrategyBuilder.initializePersistenceStrategy: ioUtils={0}", ioUtils);
        AppSerializingTranscoder transcoder = new AppSerializingTranscoder();
        transcoder.setIoUtils(ioUtils);
        manager.setTranscoder(transcoder);
        // in configuration is in minutes but in manager in ms
        manager.setMaxAccessTimeNotSaving(maxAccessTimeNotSaving * 60000L);
        // TODO: set more values
        StandardContext sctx = (StandardContext) ctx;
        if (!sctx.isSessionTimeoutOveridden()) {
            log.log(Level.FINE,
                    "MemManagerStrategyBuilder.initializePersistenceStrategy: sessionMaxInactiveInterval {0}",
                    this.sessionMaxInactiveInterval);
            manager.setMaxInactiveInterval(this.sessionMaxInactiveInterval);
        }
        ctx.setManager(manager);
        // add the mem manager valve
        //log.fine("MemManagerStrategyBuilder.initializePersistenceStrategy: adding MemManagerValve");
        //MemManagerValve memValve = new MemManagerValve();
        //sctx.addValve((GlassFishValve)memValve);
        //log.fine("MemManagerStrategyBuilder.initializePersistenceStrategy: exit");
    }
    
    /**
     * Overriden method to read extra parameter for couchbase manager.
     * The method call super and then re-parse the configuration to
     * assign local properties.
     * 
     * @param ctx The context
     * @param smBean The bean
     */
    @Override
    public void readWebAppParams(Context ctx, SessionManager smBean ) { 
        log.fine("MemManagerStrategyBuilder.readWebAppParams: init");
        // read normal properties
        super.readWebAppParams(ctx, smBean);
        // TODO: add more parameters to read from the config
        //       This method is in BasePersistenceStrategyBuilder
        if (smBean != null) {
            // read extra parameters
            ManagerProperties mgrBean = smBean.getManagerProperties();
            if ((mgrBean != null) && (mgrBean.sizeWebProperty() > 0)) {
                for (WebProperty prop : mgrBean.getWebProperty()) {
                    String name = prop.getAttributeValue(WebProperty.NAME);
                    String value = prop.getAttributeValue(WebProperty.VALUE);
                    if (name.equalsIgnoreCase(PROP_REPOSITORY_URL)) {
                        log.log(Level.FINE, "repositoryUrl: {0}", value);
                        repositoryUrl = value;
                    } else if (name.equalsIgnoreCase(PROP_REPOSITORY_BUCKET)) {
                        log.log(Level.FINE, "repositoryBucket: {0}", value);
                        repositoryBucket = value;
                    } else if (name.equalsIgnoreCase(PROP_REPOSITORY_USERNAME)) {
                        log.log(Level.FINE, "repositoryUsername: {0}", value);
                        repositoryUsername = value;
                    } else if (name.equalsIgnoreCase(PROP_REPOSITORY_PASSWORD)) {
                        log.log(Level.FINE, "repositoryPassword: {0}", value);
                        repositoryPassword = value;
                    } else if (name.equalsIgnoreCase(PROP_STICKY)) {
                        log.log(Level.FINE, "sticky: {0}", value);
                        sticky = Boolean.parseBoolean(value);
                    } else if (name.equalsIgnoreCase(PROP_LOCK_TIME)) {
                        log.log(Level.FINE, "lockTime: {0}", value);
                        try {
                            lockTime = Integer.parseInt(value);
                            if (lockTime <= 0) {
                                log.log(Level.WARNING, "Invalid integer format for lockTime {0}", value);
                                lockTime = DEFAULT_LOCK_TIME;
                            }
                        } catch (NumberFormatException e) {
                            log.log(Level.WARNING, "Invalid integer format for lockTime {0}", value);
                        }
                    }  else if (name.equalsIgnoreCase(PROP_MAX_ACCESS_TIME_NOT_SAVING)) {
                        log.log(Level.FINE, "maxAccessTimeNotSaving: {0}", value);
                        try {
                            maxAccessTimeNotSaving = Integer.parseInt(value);
                            if (maxAccessTimeNotSaving < 0) {
                                log.log(Level.WARNING, "Invalid integer format for maxAccessTimeNotSaving {0}", value);
                                maxAccessTimeNotSaving = DEFAULT_MAX_ACCESS_TIME_NOT_SAVING;
                            }
                        } catch (NumberFormatException e) {
                            log.log(Level.WARNING, "Invalid integer format for maxAccessTimeNotSaving {0}", value);
                        }
                    }  else if (name.equalsIgnoreCase(PROP_OPERATION_TIMEOUT)) {
                        log.log(Level.FINE, "operationTimeout: {0}", value);
                        try {
                            operationTimeout = Long.parseLong(value);
                            if (operationTimeout < 0) {
                                log.log(Level.WARNING, "Invalid long format for operationTimeout {0}", value);
                                operationTimeout = DEFAULT_OPERATION_TIMEOUT;
                            }
                        } catch (NumberFormatException e) {
                            log.log(Level.WARNING, "Invalid long format for operationTimeout {0}", value);
                        }
                    }
                }
            }
        }
        log.fine("MemManagerStrategyBuilder.readWebAppParams: exit");
    }
}
