package org.openmrs.module.webservices.rest.web.resource.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.ConversionUtil;
import org.openmrs.module.webservices.rest.web.RequestContext;
import org.openmrs.module.webservices.rest.web.annotation.RepHandler;
import org.openmrs.module.webservices.rest.web.annotation.Resource;
import org.openmrs.module.webservices.rest.web.representation.NamedRepresentation;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.resource.api.CrudResource;
import org.openmrs.module.webservices.rest.web.resource.api.DelegateConverter;
import org.openmrs.module.webservices.rest.web.resource.api.RepresentationDescription;
import org.openmrs.module.webservices.rest.web.response.ConversionException;
import org.openmrs.module.webservices.rest.web.response.IllegalPropertyException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.module.webservices.rest.web.response.ResponseException;
import org.springframework.util.ReflectionUtils;

/**
 * A base implementation of a {@link CrudResource} that delegates CRUD operations to a wrapped object
 * @param <T> the class we're delegating to
 */
public abstract class DelegatingCrudResource<T> implements CrudResource, DelegateConverter<T> {
	
	protected final Log log = LogFactory.getLog(getClass());

	/**
	 * Gets the delegate object with the given unique id. Implementations may decide whether "unique id" means
	 * a uuid, or if they also want to retrieve delegates based on a unique human-readable property. 
	 * @param uniqueId
	 * @return the delegate for the given uniqueId
	 */
	@Override
	public abstract T getByUniqueId(String uniqueId);
	
	/**
     * Writes the delegate to the database
     * @return the saved instance
     */
    protected abstract T save(T delegate);

	/**
     * @return a new instance of the delegate class
     */
    protected abstract T newDelegate();
    
    /**
     * Gets the {@link RepresentationDescription} for the given representation for this resource, if it exists
     * @param rep
     * @return
     */
    public abstract DelegatingResourceRepresentation getRepresentationDescription(Representation rep);
    
    /**
     * Implementations should override this method if they support sub-resources
     * @return a list of properties available as sub-resources, if any
     */
    protected List<String> propertiesToExposeAsSubResources() {
    	return null;
    }
    
    /**
	 * Assumes that the delegate property is the already-retrieved object with the given uuid
	 * @see org.openmrs.module.webservices.rest.web.resource.api.Retrievable#retrieve(java.lang.String, org.openmrs.module.webservices.rest.web.representation.Representation)
	 */
	@Override
	public Object retrieve(String uuid, RequestContext context) throws ResponseException {
		T delegate = getByUniqueId(uuid);
		if (delegate == null)
			throw new ObjectNotFoundException();
		
		return asRepresentation(delegate, context.getRepresentation());
	}
	
	/**
	 * @see org.openmrs.module.webservices.rest.web.resource.api.Creatable#create(org.springframework.web.context.request.WebRequest)
	 */
	@Override
	public Object create(SimpleObject propertiesToCreate, RequestContext context) throws ResponseException {
		T delegate = newDelegate();
		ConversionUtil.setConvertedProperties(delegate, propertiesToCreate);
		delegate = save(delegate);
		return ConversionUtil.convertToRepresentation(delegate, Representation.DEFAULT);
	}
	
	/**
	 * @throws ResourceUpdateException 
	 * @see org.openmrs.module.webservices.rest.web.resource.api.Updatable#update(java.lang.String, org.openmrs.module.webservices.rest.SimpleObject)
	 */
	@Override
	public Object update(String uuid, SimpleObject propertiesToUpdate, RequestContext context) throws ResponseException {
	    T delegate = getByUniqueId(uuid);
	    if (delegate == null)
	    	throw new ObjectNotFoundException();
	    ConversionUtil.setConvertedProperties(delegate, propertiesToUpdate);
	    delegate = save(delegate);
	    return delegate;
	}

	/**
	 * @see org.openmrs.module.webservices.rest.web.resource.api.Deletable#delete(java.lang.String)
	 */
	@Override
	public void delete(String uuid, String reason, RequestContext context) throws ResponseException {
		T delegate = getByUniqueId(uuid);
		if (delegate == null)
			throw new ObjectNotFoundException();
	    delete(delegate, reason, context);
	}

	/**
	 * Void or retire delegate, whichever action is appropriate for the resource type.
	 * Subclasses need to override this method, which is called internally by {@link #delete(String, String, RequestContext)}.
	 * @param delegate
	 * @param reason
	 * @param context
	 * @throws ResponseException
	 */
	protected abstract void delete(T delegate, String reason, RequestContext context) throws ResponseException;
	
	/**
	 * @see org.openmrs.module.webservices.rest.web.resource.api.Purgeable#purge(java.lang.String)
	 */
	@Override
	public void purge(String uuid, RequestContext context) throws ResponseException {
		T delegate = getByUniqueId(uuid);
		if (delegate == null) {
			// HTTP DELETE is idempotent, so if we can't find the object, we assume it's already deleted and return success
			return;
		}
		purge(delegate, context);
	}
	
	/**
	 * @param bean
	 * @param property
	 * @return the given property on the given bean, if it exists and is accessible. returns null otherwise.
	 */
    private Object getPropertyIfExists(Object bean, String property) {
	    try {
	    	if (PropertyUtils.isReadable(bean, property))
	    		return PropertyUtils.getProperty(bean, property);
	    } catch (Exception ex) { }
	    return null;
    }

    /**
     * Purge delegate from persistent storage.
	 * Subclasses need to override this method, which is called internally by {@link #purge(String, RequestContext)}.
     * @param delegate
     * @param context
     * @throws ResponseException
     */
    public abstract void purge(T delegate, RequestContext context) throws ResponseException;

    /**
     * TODO
     * @param delegateUuid
     * @param subResourceName
     * @param rep
     * @return
     * @throws ResponseException 
     */
    public Object listSubResource(String delegateUuid, String subResourceName, Representation rep) throws ResponseException {
    	List<String> legal = propertiesToExposeAsSubResources();
    	if (legal == null || !legal.contains(subResourceName))
    		throw new IllegalPropertyException();
    	T delegate = getByUniqueId(delegateUuid);
    	if (delegate == null)
    		throw new ObjectNotFoundException();
    	return ConversionUtil.getPropertyWithRepresentation(delegate, subResourceName, rep);
    }
    
	/**
     * Creates an object of the given representation, pulling values from fields and methods as specified
     * by a subclass 
     * @param representation
     * @return
     */
	@Override
    public Object asRepresentation(T delegate, Representation representation) throws ConversionException {
    	if (delegate == null)
   			throw new NullPointerException();

    	// first look for a method annotated to handle this representation
    	Method meth = findAnnotatedMethodForRepresentation(representation);
    	if (meth != null) {
        	try {
        		// TODO verify that the method takes 1 or 2 parameters
    	    	if (meth.getParameterTypes().length == 1)
    				return meth.invoke(this, delegate);
    			else
    				return meth.invoke(this, delegate, representation);
        	} catch (Exception ex) {
        		throw new ConversionException(null, ex);
        	}
    	}

    	// otherwise call getRepresentationDescription()
    	DelegatingResourceRepresentation repDescription = getRepresentationDescription(representation);
    	if (repDescription != null) {
    		return convertDelegateToRepresentation(delegate, repDescription);
    	}
    		
    	throw new ConversionException("Don't know how to get " + getClass().getSimpleName() + " as " + representation, null);
    }

	protected SimpleObject convertDelegateToRepresentation(T delegate, DelegatingResourceRepresentation rep) throws ConversionException {
    	if (delegate == null)
   			throw new NullPointerException();
    	SimpleObject ret = new SimpleObject();
    	for (Map.Entry<String, Representation> e : rep.getProperties().entrySet())
    		ret.put(e.getKey(), ConversionUtil.getPropertyWithRepresentation(delegate, e.getKey(), e.getValue()));
    	for (Map.Entry<String, Method> e : rep.getMethodProperties().entrySet()) {
    		try {
	            ret.put(e.getKey(), e.getValue().invoke(this, delegate));
            }
            catch (Exception ex) {
	            throw new ConversionException("method " + e.getValue(), ex);
            }
    	}
    	return ret;
    }
	
	/**
	 * Finds a method in this class or a superclass annotated with a {@link RepHandler} for the given
	 * representation
	 * @param clazz
	 * @return
	 */
	private Method findAnnotatedMethodForRepresentation(Representation rep) {
		// TODO I assume Java takes care of this, but make sure if there are multiple annotated methods we take the one on the subclass
	    for (Method method : getClass().getMethods()) {
	    	RepHandler ann = method.getAnnotation(RepHandler.class);
	    	if (ann != null) {
	    		if (ann.value().isAssignableFrom(rep.getClass())) {
		    		if (rep instanceof NamedRepresentation && !((NamedRepresentation) rep).matchesAnnotation(ann))
		    			continue;
		    		return method;
	    		}
	    	}
	    }
	    return null;
    }
	
    /**
     * Gets the URI fragment from the @RestResource annotation on the concrete subclass
     * @return
     */
    protected String getUriFragment() {
    	Resource ann = getClass().getAnnotation(Resource.class);
    	if (ann == null)
    		throw new RuntimeException("There is no " + Resource.class + " annotation on " + getClass());
    	if (StringUtils.isEmpty(ann.value()))
    		throw new RuntimeException(Resource.class.getSimpleName() + " annotation on " + getClass() + " must specify a value");
    	return ann.value();
    }
    
    /**
     * @param delegate
     * @return the URI for the given delegate object
     */
    public String getUri(T delegate) {
    	return "someprefix://" + getUriFragment() + "/" + getPropertyIfExists(delegate, "uuid");
    }
    
    protected Method findMethod(String name) {
    	// TODO replace this with something that looks specifically for a method that takes a single T argument 
    	Method ret = ReflectionUtils.findMethod(getClass(), name, (Class<?>[]) null);
    	if (ret == null)
    		throw new RuntimeException("No suitable method \"" + name + "\" in " + getClass());
    	return ret;
    }
}
