/*
 * Copyright 2012 Phil Pratt-Szeliga and other contributors
 * http://chirrup.org/
 *
 * See the file LICENSE for copying permission.
 */

package org.trifort.rootbeer.generate.bytecode;


import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.trifort.rootbeer.generate.opencl.OpenCLClass;
import org.trifort.rootbeer.generate.opencl.OpenCLScene;
import org.trifort.rootbeer.generate.opencl.OpenCLType;

import soot.Scene;
import soot.SootClass;
import soot.IntType;
import soot.VoidType;
import soot.RefType;
import soot.ArrayType;
import soot.Type;
import soot.Local;
import soot.jimple.IntConstant;
import soot.jimple.NullConstant;
import soot.rbclassload.NumberedType;
import soot.rbclassload.RootbeerClassLoader;
import soot.rbclassload.StringToType;


public class VisitorGen extends AbstractVisitorGen
{
    private       String      m_className           ;
            final SootClass   m_runtimeBasicBlock   ;
    private final Set<Type>   m_getSizeMethodsMade  ;
    private final Set<String> m_sentinalCtorsCreated;

    //Locals from code generation
    private Local m_param0;

    public VisitorGen( SootClass runtime_basic_block )
    {
        m_runtimeBasicBlock    = runtime_basic_block;
        m_getSizeMethodsMade   = new HashSet<Type>();
        m_sentinalCtorsCreated = new HashSet<String>();
    }

    public void generate()
    {
        m_bcl.push( new BytecodeLanguage() );
        makeSentinalCtors( m_sentinalCtorsCreated );
        makeSerializer();
        addGetSerializerMethod( m_bcl.top(), m_runtimeBasicBlock, m_className );
    }

    private void makeSerializer()
    {
        makeGcObjectClass();
        makeCtor();
        makeWriteStaticsToHeapMethod ( m_bcl.top()              );
        makeReadStaticsFromHeapMethod( m_bcl.top()              );
        makeGetSizeMethod            ( m_bcl.top()              );
        makeGetLengthMethod();
        makeWriteToHeapMethod        ( m_bcl.top(), m_className );
        makeReadFromHeapMethod       ( m_bcl.top(), m_className );
    }

    private void makeGcObjectClass()
    {
        String base_name = m_runtimeBasicBlock.getName();
        m_className = base_name+"Serializer";
        m_bcl.top().makeClass(m_className, "org.trifort.rootbeer.runtime.Serializer");
    }

    private void makeGetLengthMethod(){
        SootClass object_soot_class = Scene.v().getSootClass("java.lang.Object");
        m_bcl.top().startMethod("doGetSize", IntType.v(), object_soot_class.getType());
        m_thisRef = m_bcl.top().refThis();
        m_param0 = m_bcl.top().refParameter(0);

        List<Type> types = RootbeerClassLoader.v().getDfsInfo().getOrderedRefLikeTypes();
        for(Type type : types){
            makeGetSizeMethodForType(type);
        }

        m_bcl.top().returnValue(IntConstant.v(0));
        m_bcl.top().endMethod();
    }

    private void makeGetSizeMethod( final BytecodeLanguage bcl )
    {
        SootClass object_soot_class = Scene.v().getSootClass("java.lang.Object");
        bcl.startMethod("getArrayLength", IntType.v(), object_soot_class.getType());
        m_thisRef = bcl.refThis();
        m_param0 = bcl.refParameter(0);

        List<Type> types = RootbeerClassLoader.v().getDfsInfo().getOrderedRefLikeTypes();
        for ( Type type : types )
        {
            if ( ! ( type instanceof ArrayType ) )
                continue;

            String label = getNextLabel(); // non-static
            bcl.ifInstanceOfStmt( m_param0, type, label );
            bcl.returnValue( bcl.lengthof( bcl.cast( type, m_param0 ) ) );
            bcl.label( label );
        }

        bcl.returnValue(IntConstant.v(0));
        bcl.endMethod();
    }

    private void makeGetSizeMethodForType(Type type) {
        if(type instanceof ArrayType == false &&
             type instanceof RefType == false){
            return;
        }

        if(m_getSizeMethodsMade.contains(type))
            return;
        m_getSizeMethodsMade.add(type);

        if(type instanceof RefType){
            RefType ref_type = (RefType) type;
            SootClass soot_class = ref_type.getSootClass();
            if(soot_class.getName().equals("java.lang.Object"))
                return;
            if(soot_class.isInterface()){
                return;
            }
            if(differentPackageAndPrivate(ref_type)){
                return;
            }
        }

        if(typeIsPublic(type) == false)
            return;

        String label = getNextLabel();
        m_bcl.top().ifInstanceOfStmt(m_param0, type, label);

        if(type instanceof ArrayType){
            ArrayType atype = (ArrayType) type;
            Local size = m_bcl.top().local(IntType.v());
            m_bcl.top().assign(size, IntConstant.v(Constants.ArrayOffsetSize));
            Local element_size = m_bcl.top().local(IntType.v());
            OpenCLType ocl_type = new OpenCLType(atype.baseType);
            if(atype.numDimensions == 1)
                m_bcl.top().assign(element_size, IntConstant.v(ocl_type.getSize()));
            else
                m_bcl.top().assign(element_size, IntConstant.v(4));
            Local object_to_write_from = m_bcl.top().cast(type, m_param0);
            Local length = m_bcl.top().lengthof(object_to_write_from);
            m_bcl.top().mult(element_size, length);
            m_bcl.top().plus(size, element_size);
            m_bcl.top().returnValue(size);
        }else if(type instanceof RefType) {
            RefType rtype = (RefType) type;
            OpenCLClass ocl_class = OpenCLScene.v().getOpenCLClass(rtype.getSootClass());
            int size = ocl_class.getSize();
            m_bcl.top().returnValue(IntConstant.v(size));
        }
        m_bcl.top().label(label);

    }

    private static void makeWriteToHeapMethod
    (
        final BytecodeLanguage bcl,
        final String className
    )
    {
        List<Type> types = RootbeerClassLoader.v().getDfsInfo().getOrderedRefLikeTypes();
        VisitorWriteGen write_gen = new VisitorWriteGen(types,
            className, bcl );
        write_gen.makeWriteToHeapMethod();
    }

    private static void makeReadFromHeapMethod
    (
        final BytecodeLanguage bcl,
        final String className
    )
    {
        List<Type> types = RootbeerClassLoader.v().getDfsInfo().getOrderedRefLikeTypes();
        VisitorReadGen read_gen = new VisitorReadGen(types,
            className, bcl );
        read_gen.makeReadFromHeapMethod();
    }


    private static void makeWriteStaticsToHeapMethod( final BytecodeLanguage bcl )
    {
        new VisitorWriteGenStatic( bcl ).makeMethod();
    }

    private static void makeReadStaticsFromHeapMethod( final BytecodeLanguage bcl )
    {
        new VisitorReadGenStatic( bcl ).makeMethod();
    }

    private static void addGetSerializerMethod
    (
        final BytecodeLanguage bcl,
        final SootClass runtimeBasicBlock,
        final String className
    )
    {
        bcl.openClass( runtimeBasicBlock );
        SootClass gc_object_visitor_soot_class = Scene.v().getSootClass("org.trifort.rootbeer.runtime.Serializer");
        SootClass mem_cls = Scene.v().getSootClass("org.trifort.rootbeer.runtime.Memory");
        bcl.startMethod("getSerializer", gc_object_visitor_soot_class.getType(), mem_cls.getType(), mem_cls.getType());
        Local thisref = bcl.refThis();
        Local param0 = bcl.refParameter(0);
        Local param1 = bcl.refParameter(1);
        Local ret = bcl.newInstance( className, param0, param1 );
        bcl.returnValue(ret);
        bcl.endMethod();
    }

    private void makeCtor() {
        SootClass mem_cls = Scene.v().getSootClass("org.trifort.rootbeer.runtime.Memory");

        m_bcl.top().startMethod("<init>", VoidType.v(), mem_cls.getType(), mem_cls.getType());
        Local this_ref = m_bcl.top().refThis();
        Local param0 = m_bcl.top().refParameter(0);
        Local param1 = m_bcl.top().refParameter(1);
        m_bcl.top().pushMethod("org.trifort.rootbeer.runtime.Serializer", "<init>", VoidType.v(), mem_cls.getType(), mem_cls.getType());
        m_bcl.top().invokeMethodNoRet(this_ref, param0, param1);
        m_bcl.top().returnVoid();
        m_bcl.top().endMethod();
    }

    private static void makeSentinalCtors( final Set<String> sentinalCtorsCreated )
    {
        List<RefType> types = RootbeerClassLoader.v().getDfsInfo().getOrderedRefTypes();
        //types are ordered from largest type number to smallest
        //reverse the order for this computation because the sentinal ctors
        //need the parent to first have the sential ctor made.
        Collections.reverse( types );

        for ( final RefType ref_type : types )
        {
            AcceptableGpuTypes accept = new AcceptableGpuTypes();
            if ( ! accept.shouldGenerateCtor( ref_type.getClassName() ) )
                continue;

            final String refClassName = ref_type.getSootClass().getName();
            if ( sentinalCtorsCreated.contains( refClassName ) )
                continue;
            sentinalCtorsCreated.add( refClassName );

            final SootClass soot_class = Scene.v().getSootClass( refClassName );
            if ( ! soot_class.isApplicationClass() )
                continue;

            if ( soot_class.declaresMethod( "void <init>(org.trifort.rootbeer.runtime.Sentinal)" ) )
                continue;

            SootClass parent_class = soot_class.getSuperclass();
            parent_class = Scene.v().getSootClass(parent_class.getName());

            BytecodeLanguage bcl = new BytecodeLanguage();
            bcl.openClass(soot_class);
            bcl.startMethod("<init>", VoidType.v(), RefType.v("org.trifort.rootbeer.runtime.Sentinal"));
            Local thisref = bcl.refThis();

            String parent_name = parent_class.getName();
            if ( ! parent_class.isApplicationClass() )
            {
                if ( parent_class.declaresMethod("void <init>()") )
                {
                    bcl.pushMethod(parent_name, "<init>", VoidType.v());
                    bcl.invokeMethodNoRet(thisref);
                } else {
                    System.out.println("Library class "+parent_name+" on the GPU does not have a void constructor");
                    System.exit(-1);
                }
            } else {
                bcl.pushMethod(parent_name, "<init>", VoidType.v(), RefType.v("org.trifort.rootbeer.runtime.Sentinal"));
                bcl.invokeMethodNoRet(thisref, NullConstant.v());
            }
            bcl.returnVoid();
            bcl.endMethod();

        }
    }

    String getClassName() { return m_className; }
}
