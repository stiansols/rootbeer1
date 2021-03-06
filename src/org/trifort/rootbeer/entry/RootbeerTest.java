/*
 * Copyright 2012 Phil Pratt-Szeliga and other contributors
 * http://chirrup.org/
 *
 * See the file LICENSE for copying permission.
 */

package org.trifort.rootbeer.entry;


import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import org.trifort.rootbeer.configuration.Configuration;
import org.trifort.rootbeer.runtime.Rootbeer;
import org.trifort.rootbeer.test.RootbeerTestAgent;
import org.trifort.rootbeer.util.CurrJarName;
import org.trifort.rootbeer.util.ForceGC;

import soot.G;
import soot.Modifier;


public final class RootbeerTest
{
    private final static String destJAR = "output.jar";

    /**
     * If not test_case specified, then run all
     */
    public static void runTests
    (
              String        test_case       ,
        final Configuration configuration   ,
        final boolean       run_hard_tests  ,
        final boolean       run_large_mem_tests
    )
    {
        RootbeerCompiler compiler = new RootbeerCompiler( configuration );
        CurrJarName jar_name = new CurrJarName();
        String rootbeer_jar = jar_name.get();
        try
        {
            if ( test_case == null )
                compiler.compile( rootbeer_jar, destJAR, true );
            else
                compiler.compile( rootbeer_jar, destJAR, test_case );

            test_case = compiler.getProvider();

            //clear out the memory used by soot and compiler
            compiler = null;
            G.reset();
            ForceGC.gc();

            runTestCases( test_case, run_hard_tests, run_large_mem_tests );

        } catch ( Exception ex )
        {
            ex.printStackTrace();
            System.exit(-1);
        }
    }

    public static void repeatTests()
    {
        try {
            runTestCases( null, false, false );
        } catch(Exception ex){
            ex.printStackTrace();
            System.exit(-1);
        }
    }

    private static void runTestCases
    (
        final String  test_case,
        final boolean run_hard_tests,
        final boolean run_large_mem_tests
    ) throws Exception
    {
        JarClassLoader loader_factory = new JarClassLoader(destJAR);
        ClassLoader cls_loader = loader_factory.getLoader();
        Thread.currentThread().setContextClassLoader(cls_loader);

        // @todo wtf is soot used to simply call RootbeerTestAgent.test(...) ???
        Class agent_class = cls_loader.loadClass("org.trifort.rootbeer.test.RootbeerTestAgent");
        Object agent_obj = agent_class.newInstance();
        Method[] methods = agent_class.getMethods();
        if ( test_case == null )
        {
            Method test_method = findMethodByName("test", methods);
            test_method.invoke( agent_obj, cls_loader, run_hard_tests, run_large_mem_tests );
        }
        else
        {
            Method test_method = findMethodByName("testOne", methods);
            test_method.invoke( agent_obj, cls_loader, test_case );
        }
        /*if ( test_case == null )
            new RootbeerTestAgent().test( cls_loader, run_hard_tests, run_large_mem_tests );
        else
            new RootbeerTestAgent().testOne( cls_loader, test_case );*/
    }


    private static Method findMethodByName( final String name, final Method[] methods )
    {
        for ( final Method method : methods )
        {
            if ( method.getName().equals(name) )
                return method;
        }
        return null;
    }
}
