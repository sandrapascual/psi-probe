/*
 * Licensed under the GPL License. You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * THIS PACKAGE IS PROVIDED "AS IS" AND WITHOUT ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * WITHOUT LIMITATION, THE IMPLIED WARRANTIES OF MERCHANTIBILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE.
 */

package com.googlecode.psiprobe;

import com.googlecode.psiprobe.model.ApplicationParam;
import com.googlecode.psiprobe.model.ApplicationResource;
import com.googlecode.psiprobe.model.FilterInfo;
import com.googlecode.psiprobe.model.FilterMapping;

import org.apache.catalina.Context;
import org.apache.catalina.Valve;
import org.apache.catalina.deploy.ApplicationParameter;
import org.apache.catalina.deploy.ContextResource;
import org.apache.catalina.deploy.ContextResourceLink;
import org.apache.catalina.deploy.FilterDef;
import org.apache.catalina.deploy.FilterMap;
import org.apache.catalina.deploy.NamingResources;
import org.apache.commons.beanutils.ConstructorUtils;
import org.apache.jasper.JspCompilationContext;
import org.apache.jasper.Options;
import org.apache.jasper.compiler.JspRuntimeContext;
import org.apache.jasper.servlet.JspServletWrapper;
import org.apache.naming.resources.Resource;
import org.apache.naming.resources.ResourceAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.naming.NamingException;
import javax.servlet.ServletContext;

/**
 * The Class Tomcat70ContainerAdapter.
 *
 * @author Vlad Ilyushchenko
 * @author Mark Lewis
 */
public class Tomcat70ContainerAdapter extends AbstractTomcatContainer {

  @Override
  protected Valve createValve() {
    return new Tomcat70AgentValve();
  }

  @Override
  public boolean canBoundTo(String binding) {
    boolean canBind = false;
    if (binding != null) {
      canBind |= binding.startsWith("Apache Tomcat/7.0");
      canBind |= binding.startsWith("Apache Tomcat (TomEE)/7.0");
      canBind |= binding.startsWith("JBoss Web/3.0");
      canBind |= binding.startsWith("JBoss Web/7.0");
      canBind |= binding.startsWith("NonStop(tm) Servlets For JavaServer Pages(tm) v7.0");
      canBind |= binding.startsWith("SpringSource tc") && binding.contains("/7.0");
      canBind |= binding.startsWith("VMware vFabric tc") && binding.contains("/7.0");
      canBind |= binding.startsWith("Pivotal tc") && binding.contains("/7.0");
    }
    return canBind;
  }

  /**
   * Gets the filter mappings.
   *
   * @param fmap the fmap
   * @param dm the dm
   * @param filterClass the filter class
   * @return the filter mappings
   */
  protected List<FilterMapping> getFilterMappings(FilterMap fmap, String dm, String filterClass) {
    String[] urls = fmap.getURLPatterns();
    String[] servlets = fmap.getServletNames();
    List<FilterMapping> results = new ArrayList<FilterMapping>(urls.length + servlets.length);
    for (String url : urls) {
      FilterMapping fm = new FilterMapping();
      fm.setUrl(url);
      fm.setFilterName(fmap.getFilterName());
      fm.setDispatcherMap(dm);
      fm.setFilterClass(filterClass);
      results.add(fm);
    }
    for (String servlet : servlets) {
      FilterMapping fm = new FilterMapping();
      fm.setServletName(servlet);
      fm.setFilterName(fmap.getFilterName());
      fm.setDispatcherMap(dm);
      fm.setFilterClass(filterClass);
      results.add(fm);
    }
    return results;
  }

  @Override
  protected JspCompilationContext createJspCompilationContext(String name, Options opt,
      ServletContext sctx, JspRuntimeContext jrctx, ClassLoader classLoader) {

    JspCompilationContext jcctx;
    try {
      jcctx = new JspCompilationContext(name, opt, sctx, null, jrctx);
    } catch (NoSuchMethodError err) {
      /*
       * The above constructor's signature changed in Tomcat 7.0.16:
       * http://svn.apache.org/viewvc?view=revision&revision=1124719
       * 
       * If we reach this point, we are running on a prior version of Tomcat 7 and must use
       * reflection to create this object.
       */
      try {
        jcctx =
            ConstructorUtils.invokeConstructor(JspCompilationContext.class,
                new Object[] {name, false, opt, sctx, null, jrctx}, new Class[] {String.class,
                    Boolean.TYPE, Options.class, ServletContext.class, JspServletWrapper.class,
                    JspRuntimeContext.class});
        return jcctx;
      } catch (NoSuchMethodException ex) {
        throw new RuntimeException(ex);
      } catch (IllegalAccessException ex) {
        throw new RuntimeException(ex);
      } catch (InvocationTargetException ex) {
        throw new RuntimeException(ex);
      } catch (InstantiationException ex) {
        throw new RuntimeException(ex);
      }
    }
    if (classLoader != null) {
      jcctx.setClassLoader(classLoader);
    }
    return jcctx;
  }

  @Override
  public void addContextResourceLink(Context context, List<ApplicationResource> resourceList,
      boolean contextBound) {
    
    NamingResources namingResources = context.getNamingResources();
    for (ContextResourceLink link : namingResources.findResourceLinks()) {
      ApplicationResource resource = new ApplicationResource();
      logger.debug("reading resourceLink: " + link.getName());
      resource.setApplicationName(context.getName());
      resource.setName(link.getName());
      resource.setType(link.getType());
      resource.setLinkTo(link.getGlobal());
      // lookupResource(resource, contextBound, false);
      resourceList.add(resource);
    }
  }

  @Override
  public void addContextResource(Context context, List<ApplicationResource> resourceList,
      boolean contextBound) {
    NamingResources namingResources = context.getNamingResources();
    for (ContextResource contextResource : namingResources.findResources()) {
      ApplicationResource resource = new ApplicationResource();
      logger.info("reading resource: " + contextResource.getName());
      resource.setApplicationName(context.getName());
      resource.setName(contextResource.getName());
      resource.setType(contextResource.getType());
      resource.setScope(contextResource.getScope());
      resource.setAuth(contextResource.getAuth());
      resource.setDescription(contextResource.getDescription());
      // lookupResource(resource, contextBound, false);
      resourceList.add(resource);
    }
  }

  @Override
  public List<FilterMapping> getApplicationFilterMaps(Context context) {
    FilterMap[] fms = context.findFilterMaps();
    List<FilterMapping> filterMaps = new ArrayList<FilterMapping>(fms.length);
    for (FilterMap filterMap : fms) {
      if (filterMap != null) {
        String dm;
        switch (filterMap.getDispatcherMapping()) {
          case FilterMap.ERROR:
            dm = "ERROR";
            break;
          case FilterMap.FORWARD:
            dm = "FORWARD";
            break;
          // case FilterMap.FORWARD_ERROR: dm = "FORWARD,ERROR"; break;
          case FilterMap.INCLUDE:
            dm = "INCLUDE";
            break;
          // case FilterMap.INCLUDE_ERROR: dm = "INCLUDE,ERROR"; break;
          // case FilterMap.INCLUDE_ERROR_FORWARD: dm = "INCLUDE,ERROR,FORWARD"; break;
          // case FilterMap.INCLUDE_FORWARD: dm = "INCLUDE,FORWARD"; break;
          case FilterMap.REQUEST:
            dm = "REQUEST";
            break;
          // case FilterMap.REQUEST_ERROR: dm = "REQUEST,ERROR"; break;
          // case FilterMap.REQUEST_ERROR_FORWARD: dm = "REQUEST,ERROR,FORWARD"; break;
          // case FilterMap.REQUEST_ERROR_FORWARD_INCLUDE: dm = "REQUEST,ERROR,FORWARD,INCLUDE";
          // break;
          // case FilterMap.REQUEST_ERROR_INCLUDE: dm = "REQUEST,ERROR,INCLUDE"; break;
          // case FilterMap.REQUEST_FORWARD: dm = "REQUEST,FORWARD"; break;
          // case FilterMap.REQUEST_INCLUDE: dm = "REQUEST,INCLUDE"; break;
          // case FilterMap.REQUEST_FORWARD_INCLUDE: dm = "REQUEST,FORWARD,INCLUDE"; break;
          default:
            dm = "";
        }

        String filterClass = "";
        FilterDef fd = context.findFilterDef(filterMap.getFilterName());
        if (fd != null) {
          filterClass = fd.getFilterClass();
        }

        List<FilterMapping> filterMappings = getFilterMappings(filterMap, dm, filterClass);
        filterMaps.addAll(filterMappings);
      }
    }
    return filterMaps;
  }

  @Override
  public List<FilterInfo> getApplicationFilters(Context context) {
    FilterDef[] fds = context.findFilterDefs();
    List<FilterInfo> filterDefs = new ArrayList<FilterInfo>(fds.length);
    for (FilterDef filterDef : fds) {
      if (filterDef != null) {
        FilterInfo fi = getFilterInfo(filterDef);
        filterDefs.add(fi);
      }
    }
    return filterDefs;
  }

  /**
   * Gets the filter info.
   *
   * @param fd the fd
   * @return the filter info
   */
  private static FilterInfo getFilterInfo(FilterDef fd) {
    FilterInfo fi = new FilterInfo();
    fi.setFilterName(fd.getFilterName());
    fi.setFilterClass(fd.getFilterClass());
    fi.setFilterDesc(fd.getDescription());
    return fi;
  }

  @Override
  public List<ApplicationParam> getApplicationInitParams(Context context) {
    /*
     * We'll try to determine if a parameter value comes from a deployment descriptor or a context
     * descriptor.
     *
     * Assumption: context.findParameter() returns only values of parameters that are declared in a
     * deployment descriptor.
     *
     * If a parameter is declared in a context descriptor with override=false and redeclared in a
     * deployment descriptor, context.findParameter() still returns its value, even though the value
     * is taken from a context descriptor.
     *
     * context.findApplicationParameters() returns all parameters that are declared in a context
     * descriptor regardless of whether they are overridden in a deployment descriptor or not or
     * not.
     */
    /*
     * creating a set of parameter names that are declared in a context descriptor and can not be
     * ovevridden in a deployment descriptor.
     */
    Set<String> nonOverridableParams = new HashSet<String>();
    for (ApplicationParameter appParam : context.findApplicationParameters()) {
      if (appParam != null && !appParam.getOverride()) {
        nonOverridableParams.add(appParam.getName());
      }
    }
    List<ApplicationParam> initParams = new ArrayList<ApplicationParam>();
    ServletContext servletCtx = context.getServletContext();
    for (Enumeration<String> e = servletCtx.getInitParameterNames(); e.hasMoreElements();) {
      String paramName = e.nextElement();
      ApplicationParam param = new ApplicationParam();
      param.setName(paramName);
      param.setValue(servletCtx.getInitParameter(paramName));
      /*
       * if the parameter is declared in a deployment descriptor and it is not declared in a context
       * descriptor with override=false, the value comes from the deployment descriptor
       */
      param.setFromDeplDescr(context.findParameter(paramName) != null
          && !nonOverridableParams.contains(paramName));
      initParams.add(param);
    }
    return initParams;
  }

  @Override
  public boolean resourceExists(String name, Context context) {
    try {
      return context.getResources().lookup(name) != null;
    } catch (NamingException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public InputStream getResourceStream(String name, Context context) throws IOException {
    try {
      return ((Resource) context.getResources().lookup(name)).streamContent();
    } catch (NamingException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public Long[] getResourceAttributes(String name, Context context) {
    Long[] result = new Long[2];
    try {
      ResourceAttributes resource = (ResourceAttributes) context.getResources().getAttributes(name);
      result[0] = resource.getContentLength();
      result[1] = resource.getLastModified();
    } catch (NamingException ex) {
      // Don't care
    }
    return result;
  }

  @Override
  protected Object getNamingToken(Context context) {
    return context;
  }

}