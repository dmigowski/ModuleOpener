package de.ikoffice.util;


import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Field;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sun.misc.Unsafe;

/**
 * Welcome to another absurdity of the coding world where I wasted a day just for not having to think about these Java 9
 * opens architecture ever again, except when I forget to call ModuleOpener.openAllModules() at the beginning of my 
 * code. 
 * 
 * You still might have to do the --add-exports you need so the compiler does not complain if you want to use classes in
 * hidden packages in the core modules (like e.g. the X509-stuff).
 * 
 * We do not use our logging framework here because this can opener should be used even before the logging is activated
 * so we don't have to think about package access even so soon in the runtime of the application.
 */
public class ModuleOpener {

	private static Lookup lookup = MethodHandles.lookup();
	
	private final static boolean DEBUG = false || System.getenv("ModuleOpener.DEBUG") != null;
	
	private Set<Object> allSet;		
	private VarHandle exportedPackagesVH;
	private VarHandle openPackagesVH;
	private Module javaBaseModule;
	private ModuleLayer moduleLayer;
	
	public ModuleOpener() throws Exception {
		moduleLayer = ModuleLayer.boot();
		javaBaseModule = moduleLayer.modules().stream().filter(m->m.getName().equals("java.base")).findFirst().get();

		// java.base/java.lang must be open to at least ALL-UNNAMED
		ensureJavaBaseJavaLangIsAlreadyOpenToUsAndTheCallInit();
	}

	private void ensureJavaBaseJavaLangIsAlreadyOpenToUsAndTheCallInit() throws Exception {
		Module ourModule = ModuleOpener.class.getModule();
		if( javaBaseModule.isOpen("java.lang",ourModule) ) {
			debug("java.base/java.lang already open to '"+getName(ourModule)+"'.");
			init();
			return;
		} 
		debug("java.base/java.lang is not open to '"+getName(ourModule)+"' yet!");

		// Okay, we have to do the dirty stuff. at first we have to determine the field offset of some Object fields 
		// in a class.
		@SuppressWarnings("unused")
		Object o = new Object() { String a; String b; String c; };
		Class<?> clazz = o.getClass();
		Field a = clazz.getDeclaredField("a");
		Field b = clazz.getDeclaredField("b");
		Field c = clazz.getDeclaredField("c");
		List<Long> fieldOffsets = new ArrayList<>();
		Unsafe unsafe = getTheUnsafe();
		long offsetA = unsafe.objectFieldOffset(a);
		long offsetB = unsafe.objectFieldOffset(b);
		long offsetC = unsafe.objectFieldOffset(c);
		fieldOffsets.add(Math.abs(offsetA-offsetB));
		fieldOffsets.add(Math.abs(offsetA-offsetC));
		fieldOffsets.add(Math.abs(offsetB-offsetC));
		Collections.sort(fieldOffsets);
		long offsetDelta = fieldOffsets.get(0);
		long offsetDelta2 = fieldOffsets.get(1);
		long offsetDelta3 = fieldOffsets.get(2);
		if( offsetDelta != offsetDelta2 || offsetDelta2 != offsetDelta3 / 2 ) {
			throw new RuntimeException("We cannot reliably determine the size of an Object field here.");
		}
		debug("The object field size on this machine seems to be "+offsetDelta+" bytes.");
		if( offsetDelta != 4 ) {
			throw new RuntimeException("Sadly we only support 4 bytes for now.");
		}
		
		// Now we need to find the offset of the name of javaBaseModule module and... ehm... have to unname it a bit
		String name = javaBaseModule.getName();
		a.set(o, name);
		int ref = unsafe.getInt(o, offsetA);
		long nameOffset = 0;
		for( int i = 12; i<36; i+=4 ) { // We should find a result at 20 or 28
			int ref2 = unsafe.getInt(javaBaseModule, i); // Sweaty palms...
			if( ref2 == ref ) {
				nameOffset = i;
			}
		}
		if( nameOffset == 0 ) {
			throw new RuntimeException("Sadly we didn't find the offset of the name attribute in the Module class.");
		}
		debug("The name attribute can be found at offset "+nameOffset+" in the Module.class!");
		Object nameByUnsafe = unsafe.getObject(javaBaseModule, nameOffset);
		if( !nameByUnsafe.equals(name) ) {
			throw new RuntimeException("Still the object retrieved from that offset is not the name.");
		}
	
		// Now we make the base module unnamed very quick and reset it's name, so we can get the accessors to the hidden
		// fields in Module.class!
		unsafe.putObject(javaBaseModule, nameOffset, null);
		init();
		unsafe.putObject(javaBaseModule, nameOffset, nameByUnsafe);
	}
	
	private void init() throws Exception {
		exportedPackagesVH = varHandle(Module.class, "exportedPackages", Map.class);
		openPackagesVH = varHandle(Module.class, "openPackages", Map.class);
	    allSet = new HashSet<>();
		allSet.add( varHandledGetStatic(Module.class, "ALL_UNNAMED_MODULE", Module.class));
		allSet.add( varHandledGetStatic(Module.class, "EVERYONE_MODULE", Module.class));
		debug("Initialization completed.");
	}
	
	public void openAndExportAllToAll() throws Exception {
		// Do --add-opens=java.base/java.lang=ALL-UNNAMED first! 
		openAndExportToAll(javaBaseModule,"java.lang");
		
		// Now all the other modules and packages
		for( Module module : moduleLayer.modules() ) {
			openAndExportToAll(module);	
		}
	}
	
	public void openAndExportToAll(Module module) throws Exception {
		for( String pakkage : module.getPackages() ) {
			openAndExportToAll(module, pakkage);
		}
	}

	public void openAndExportToAll(Module module, String pkgName) throws Exception {
		debug("Opening module '"+module.getName()+"' to '"+pkgName+"'!");
		Map<String, Set<?>> pckgForModule;
		
		pckgForModule = (Map<String, Set<?>>) exportedPackagesVH.get(module);
		if (pckgForModule == null) {
			pckgForModule = new HashMap<>();
			exportedPackagesVH.set(module, pckgForModule);
		}
		pckgForModule.put(pkgName, allSet);
		
		pckgForModule = (Map<String, Set<?>>) openPackagesVH.get(module);
		if (pckgForModule == null) {
			pckgForModule = new HashMap<>();
			openPackagesVH.set(module, pckgForModule);
		}
		pckgForModule.put(pkgName, allSet);
		
		// This does not seem to be needed, but burningwave did this when they exported modules and just in case there
		// is some rare circumstance I don't see yet I will call this function also!
		MethodType mt = MethodType.methodType(void.class, Module.class, String.class);
		Lookup l = MethodHandles.lookup();
		Lookup privLookup = MethodHandles.privateLookupIn(Module.class,l);
		MethodHandle mh = privLookup.findStatic(Module.class, "addExportsToAll0", mt);
		try {
			mh.invoke(module, pkgName);
		} catch (Throwable e) {
			throw new RuntimeException("Calling Module.addExportsToAll0(module,packageName) failed.",e);
		}
	}
	
	private static Object varHandledGetStatic(Class<?> clazz, String fieldName, Class<?> fieldType) throws Exception {
		// Using var and method handles allows us to find fields that are otherwise hidden from the java.lang.reflect 
		// API. This is the case with the attributes of important classes like Module.class.
		Lookup privLookup = MethodHandles.privateLookupIn(clazz,lookup);
		VarHandle varHandle = privLookup.findStaticVarHandle(clazz, fieldName, fieldType);
		return varHandle.get();
	}

	private static VarHandle varHandle(Class<?> clazz, String fieldName, Class<?> fieldType) throws Exception {
		Lookup privLookup = MethodHandles.privateLookupIn(clazz,lookup);
		VarHandle varHandle = privLookup.findVarHandle(clazz, fieldName, fieldType);
		return varHandle;
	}
	
	private static boolean modulesOpened;
	
	public static synchronized void openAllModules() {
		if( modulesOpened ) {
			return;
		}
        try {
			new ModuleOpener().openAndExportAllToAll();
	        modulesOpened = true;
	        System.out.println("ModuleOpener: Opened all modules!");
		} catch (Exception e) {
			System.err.println("Opening all the modules seemed to fail!");
			e.printStackTrace(System.err);
		}
	}

	private static String getName(Module module) {
	    String name = module.getName();
	    return name == null ? "ALL-UNNAMED" : name; //$NON-NLS-1$
	}
	
	private static void debug(String msg) {
		if( DEBUG ) {
			System.out.println("ModuleOpener: "+msg);
		}
	}

	public static Unsafe getTheUnsafe() throws Exception {
		Lookup l = MethodHandles.lookup();
		Lookup privLookup = MethodHandles.privateLookupIn(Unsafe.class,l);
		VarHandle varHandle = privLookup.findStaticVarHandle(Unsafe.class, "theUnsafe", Unsafe.class);
		return (Unsafe) varHandle.get();
	}
	
}
