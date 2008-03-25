package org.jvnet.hk2.config;

import com.sun.hk2.component.Holder;
import com.sun.hk2.component.InhabitantsFile;
import org.jvnet.hk2.component.ComponentException;
import org.jvnet.hk2.component.Inhabitant;
import org.jvnet.hk2.component.MultiMap;
import org.jvnet.tiger_types.Types;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.logging.Logger;

/**
 * Describes the configuration model for a particular class (called "target type" in this class.)
 *
 * TODO: we need to remember if element values are single-valued or multi-valued.
 *
 * @author Kohsuke Kawaguchi
 */
public final class ConfigModel {
    /**
     * Reference to the {@link ConfigInjector} used to inject values to
     * objects of this model.
     */
    public final Inhabitant<? extends ConfigInjector> injector;

    /**
     * Legal attribute names.
     */
    final Map<String,AttributeLeaf> attributes = new HashMap<String,AttributeLeaf>();

    /**
     * Legal child element names and how they should be handled
     */
    final Map<String,Property> elements = new HashMap<String,Property>();

    /**
     * Contracts under which the inhabitant should be registered.
     */
    final List<String> contracts;

    /**
     * Type names for which this type creates a symbol space.
     */
    final Set<String> symbolSpaces;

    /**
     * The element name of this model itself, if this element can appear globally.
     * Otherwise null.
     * <p>
     * Note that in many circumstances the tag name is determined by the parent element,
     * even if a {@link ConfigModel} has a tag name.
     */
    final String tagName;

    /**
     * Deferred reference to the class loader that loaded the injector.
     * This classloader can also load the configurable object.
     */
    public final Holder<ClassLoader> classLoaderHolder = new Holder<ClassLoader>() {
        public ClassLoader get() {
            return injector.get().getClass().getClassLoader();
        }
    };

    /**
     * Fully-qualified name of the target type that this injector works on.
     */
    public final String targetTypeName;

    /**
     * Fully-qualified name under which this type is indexed.
     * This is the class name where the key property is defined.
     *
     * <p>
     * Null if this type is not keyed.
     */
    public final String keyedAs;

    /**
     * If this model has any property that works as a key.
     *
     * @see ConfigMetadata#KEY
     */
    public final String key;
    
    public Set<String> getAttributeNames() {
        return Collections.unmodifiableSet( attributes.keySet() );
    }

    /**
     * Performs injection to the given object.
     */
    /*package*/ void inject(Dom dom, Object target) {
        try {
            injector.get().inject(dom,target);
        } catch (ConfigurationException e) {
            e.setLocation(dom.getLocation());
            throw e;
        }
    }

    public abstract static class Property {
        /**
         * @see #xmlName()
         */
        public final String xmlName;

        protected Property(String xmlName) {
            this.xmlName = xmlName;
        }

        /**
         * XML name of the property, like "abc-def".
         */
        public final String xmlName() {
            return xmlName;
        }

        public abstract boolean isLeaf();

        /**
         * Is multiple values allowed?
         */
        public abstract boolean isCollection();

        /**
         * Gets the value from {@link Dom} in the specified type.
         *
         * @param dom
         *      The DOM instance to get the value from.
         * @param returnType
         *      The expected type of the returned object.
         *      Valid types are (1) primitive and 'leaf' Java types, such as {@link String},
         *      (2) {@link ConfigBeanProxy}, (3) {@link Dom}, and (4) collections of any of above.
         */
        public abstract Object get(Dom dom, Type returnType);

        /**
         * Sets the value to {@link Dom}.
         *
         * @param arg
         *      The new value. See the return type of the get method for the discussion of
         *      possible types.
         */
        public abstract void set(Dom dom, Object arg);
    }

    static abstract class Node extends Property {
        final ConfigModel model;
        public Node(ConfigModel model, String xmlName) {
            super(xmlName);
            this.model = model;
        }

        public boolean isLeaf() {
            return false;
        }

        /**
         * Coerce the given type to {@link Dom}.
         * Only handles those types that are valid to the {@link #set(Dom, Object)} method.
         */
        protected final Dom toDom(Object arg) {
            if(arg==null)
                return null;
            if(arg instanceof Dom)
                return (Dom)arg;
            if(arg instanceof ConfigBeanProxy)
                return Dom.unwrap((ConfigBeanProxy)arg);
            throw new IllegalArgumentException("Unexpected type "+arg.getClass()+" for "+xmlName);
        }
    }

    static final class CollectionNode extends Node {
        CollectionNode(ConfigModel model, String xmlName) {
            super(model, xmlName);
        }

        public boolean isCollection() {
            return true;
        }

        public Object get(final Dom dom, Type returnType) {
            // TODO: perhaps support more collection types?


            if(!(returnType instanceof ParameterizedType))
                throw new IllegalArgumentException("List needs to be parameterized");
            final Class itemType = Types.erasure(Types.getTypeArgument(returnType,0));

            final List<Dom> v = ("*".equals(xmlName)?dom.domNodeByTypeElements(itemType):dom.nodeElements(xmlName));

            if(itemType==Dom.class)
                // TODO: this returns a view, not a live list
                return v;
            if(ConfigBeanProxy.class.isAssignableFrom(itemType)) {
                // return a live list
                return new AbstractList<Object>() {
                    public Object get(int index) {
                        return v.get(index).createProxy();
                    }

                    public void add(int index, Object element) {
                        // update the master children list, as well as this view 'v'
                        Dom child = Dom.unwrap((ConfigBeanProxy) element);
                        dom.insertAfter( index==0 ? null : v.get(index-1), xmlName, child);
                        v.add(index,child);
                    }

                    public Object remove(int index) {
                        Dom child = v.get(index);
                        dom.removeChild(child);
                        v.remove(index);
                        return child.createProxy();
                    }

                    public Object set(int index, Object element) {
                        Dom child = Dom.unwrap((ConfigBeanProxy) element);
                        dom.replaceChild( v.get(index), xmlName, child );
                        return v.set(index,child).createProxy();
                    }

                    public int size() {
                        return v.size();
                    }
                };
            }

            // TODO: error check needs to be improved,
            // as itemType might be inconsistent with the actual type
            return new AbstractList() {
                public Object get(int index) {
                    return v.get(index).get();
                }

                public int size() {
                    return v.size();
                }
            };
        }

        public void set(Dom dom, Object _arg) {
            if(!(_arg instanceof List))
                throw new IllegalArgumentException("Expecting a list but found "+_arg);
            List arg = (List)_arg;

            Dom[] values = new Dom[arg.size()];
            int i=0;
            for (Object o : arg)
                values[i++] = toDom(o);

            dom.setNodeElements(xmlName,values);
        }
    }

    static final class SingleNode extends Node {
        SingleNode(ConfigModel model, String xmlName) {
            super(model, xmlName);
        }

        public boolean isCollection() {
            return false;
        }

        public Object get(Dom dom, Type returnType) {
            Dom v = dom.nodeElement(xmlName);
            if(v==null)     return null;

            if(returnType==Dom.class)
                return v;

            Class rt = Types.erasure(returnType);
            if(ConfigBeanProxy.class.isAssignableFrom(rt))
                return v.createProxy();

            throw new IllegalArgumentException("Invalid type "+returnType+" for "+xmlName);
        }

        public void set(Dom dom, Object arg) {
            Dom child = toDom(arg);

            if(child==null) // remove
                dom.setNodeElements(xmlName);
            else // replace
                dom.setNodeElements(xmlName,child);
        }
    }

    static abstract class Leaf extends Property {
        public Leaf(String xmlName) {
            super(xmlName);
        }

        public boolean isLeaf() {
            return true;
        }

        /**
         * Converts a single value from string to the specified target type.
         *
         * @return
         *      Instance of the given 'returnType'
         */
        protected static Object convertLeafValue(Class<?> returnType, String v) {
            if(v==null)
                // TODO: default value handling
                // TODO: if primitive types, report an error
                return null;

            if(returnType==String.class) {
                return v;
            }
            if(returnType==Integer.class || returnType==int.class) {
                return Integer.valueOf(v);
            }
            if(returnType==Boolean.class || returnType==boolean.class) {
                return BOOLEAN_TRUE.contains(v);
            }
            throw new IllegalArgumentException("Don't know how to handle "+returnType);
        }

        private static final Set<String> BOOLEAN_TRUE = new HashSet<String>(Arrays.asList("true","yes","on","1"));
    }

    static final class CollectionLeaf extends Leaf {
        CollectionLeaf(String xmlName) {
            super(xmlName);
        }

        public boolean isCollection() {
            return true;
        }

        public Object get(Dom dom, Type returnType) {
            // TODO: perhaps support more collection types?
            final List<String> v = dom.leafElements(xmlName);
            if(!(returnType instanceof ParameterizedType))
                throw new IllegalArgumentException("List needs to be parameterized");
            final Class itemType = Types.erasure(Types.getTypeArgument(returnType,0));

            // return a live list
            return new AbstractList<Object>() {
                public Object get(int index) {
                    return convertLeafValue(itemType,v.get(index));
                }

                public int size() {
                    return v.size();
                }
            };
        }

        public void set(Dom dom, Object arg) {
            if (arg instanceof List) {
                String[] strings = new String[((List) arg).size()];
                dom.setLeafElements(xmlName, (String[]) ((List)arg).toArray(strings));
            } else {//TODO -- I hope this is OK for now (km@dev.java.net 25 Mar 2008)
                throw new UnsupportedOperationException();
            }
        }
    }

    static class AttributeLeaf extends Leaf {

        AttributeLeaf(String xmlName) {
            super(xmlName);
        }

        /**
         * Is multiple values allowed?
         */
        public boolean isCollection() {
            return false;
        }

        /**
         * Gets the value from {@link Dom} in the specified type.
         *
         * @param dom        The DOM instance to get the value from.
         * @param returnType The expected type of the returned object.
         *                   Valid types are (1) primitive and 'leaf' Java types, such as {@link String},
         *                   (2) {@link ConfigBeanProxy}, (3) and its collections.
         */
        public Object get(Dom dom, Type returnType) {
            String v = dom.attribute(xmlName);
            return convertLeafValue(Types.erasure(returnType), v);
        }

        /**
         * Sets the value to {@link Dom}.
         */
        public void set(Dom dom, Object arg) {
            if(arg==null) {
                // TODO: implement remove
                Logger.getAnonymousLogger().severe("TODO : KK : implement remove");
                return;
            }
            dom.attribute(xmlName, arg.toString());
        }
    }

    static final class AttributeLeafWithDefaultValue extends AttributeLeaf {
        String dv;
        AttributeLeafWithDefaultValue(String xmlName, String dv) {
            super(xmlName);
            this.dv = dv;
        }
        @Override
        public Object get(Dom dom, Type rt) {
            Object value = super.get(dom, rt);
            if (value == null)
                return (dv);
            return value;
        }
    }
    
    static final class SingleLeaf extends Leaf {
        SingleLeaf(String xmlName) {
            super(xmlName);
        }

        public boolean isCollection() {
            return false;
        }

        public Object get(Dom dom, Type returnType) {
            // leaf types
            String v = dom.leafElement(xmlName);
            return convertLeafValue(Types.erasure(returnType), v);
        }

        public void set(Dom dom, Object arg) {
            if(arg==null) {
                // TODO: implement remove
                Logger.getAnonymousLogger().severe("TODO : KK : implement remove");
                return;
            }
            dom.setLeafElements(xmlName,arg.toString());
        }
    }

    /**
     * @param description
     *      The description of the model as written in {@link InhabitantsFile the inhabitants file}.
     */
    public ConfigModel(DomDocument document, Inhabitant<? extends ConfigInjector> injector, MultiMap<String,String> description) {
        if(description==null)
            throw new ComponentException("%s doesn't have any metadata",injector.type());

        document.models.put(injector,this); // register now so that cyclic references are handled correctly.
        this.injector = injector;
        String targetTypeName=null,indexTypeName=null;
        String key = null;
        for (Map.Entry<String, List<String>> e : description.entrySet()) {
            String name = e.getKey();
            String value = e.getValue().size()>0 ? e.getValue().get(0) : null;
            if(name.startsWith("@")) {
                // TODO: handle value.equals("optional") and value.equals("required") distinctively.
                String attributeName = name.substring(1);
                String dv = getDefaultFromMetaData(e.getValue());
                if (dv == null)
                    attributes.put(attributeName, new AttributeLeaf(attributeName));
                else
                    attributes.put(attributeName, new AttributeLeafWithDefaultValue(attributeName, dv));
            } else
            if(name.startsWith("<")) {
                String elementName = name.substring(1, name.length() - 1);
                elements.put(elementName,parseValue(elementName,document,value));
            } else
            if(name.equals(ConfigMetadata.TARGET))
                targetTypeName = value;
            else
            if(name.equals(ConfigMetadata.KEYED_AS))
                indexTypeName = value;
            else
            if(name.equals(ConfigMetadata.KEY))
                key = value;
        }
        if(targetTypeName==null)
            throw new ComponentException("%s doesn't have the mandatory '%s' metadata", injector.type(), ConfigMetadata.TARGET);
        if(key==null ^ indexTypeName==null)
            throw new ComponentException("%s has inconsistent '%s=%s' and '%s=%s' metadata",
                ConfigMetadata.KEY, key, ConfigMetadata.TARGET, indexTypeName);
        this.targetTypeName = targetTypeName;
        this.keyedAs = indexTypeName;
        this.key = key;
        this.contracts = description.get(ConfigMetadata.TARGET_CONTRACTS);
        this.symbolSpaces = new HashSet<String>(description.get("symbolSpaces"));

        String tagName = null;
        for (String v : description.get(InhabitantsFile.INDEX_KEY)) {
            if(v.startsWith(ELEMENT_NAME_PREFIX))
                tagName = v.substring(ELEMENT_NAME_PREFIX.length());
        }
        this.tagName = tagName;
    }

    private static final String ELEMENT_NAME_PREFIX = ConfigInjector.class.getName() + ':';

    /**
     * Finds the {@link Property} from either {@link #elements} or {@link #attributes}.
     * @param xmlName
     *      XML name to be searched.
     * @return null
     *      if none is found.
     */
    public Property findIgnoreCase(String xmlName) {
        // first try the exact match to take our chance
        Property a = attributes.get(xmlName);
        if(a!=null)     return a;
        a = elements.get(xmlName);
        if(a!=null)     return a;

        // exhaustive search
        a = _findIgnoreCase(xmlName, attributes);
        if(a!=null)     return a;
        return _findIgnoreCase(xmlName, elements);
    }

    private Property _findIgnoreCase(String name, Map<String, ? extends Property> map) {
        for (Map.Entry<String, ? extends Property> i : map.entrySet())
            if(i.getKey().equalsIgnoreCase(name))
                return i.getValue();
        return null;
    }

    /**
     * Parses {@link Property} object from a value in the metadata description.
     */
    private Property parseValue(String elementName, DomDocument document, String value) {
        boolean collection = false;
        if(value.startsWith("collection:")) {
            collection = true;
            value = value.substring(11);
        }

        if(value.equals("leaf")) {
            if(collection)  return new CollectionLeaf(elementName);
            else            return new SingleLeaf(elementName);
        }

        // this element is a reference to another configured inhabitant.
        // figure that out.
        ConfigModel model = document.buildModel(value);
        if(collection)
            return new CollectionNode(model,elementName);
        else
            return new SingleNode(model,elementName);
    }
    
    private static String getDefaultFromMetaData(List<String> strings) {
        if (strings == null || strings.size() == 0)
            return ( null );
        String dv = null, PREFIX="default:";
        for (String s : strings) {
            if (s.startsWith(PREFIX)) {
                dv = s.substring(PREFIX.length());
                break;
            }
        }
        return ( dv );
    }
}
