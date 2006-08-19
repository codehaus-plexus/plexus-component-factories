package org.codehaus.plexus.component.jruby;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.bsf.BSFException;
import org.apache.bsf.BSFManager;
import org.codehaus.classworlds.ClassRealm;
import org.codehaus.plexus.component.factory.ComponentInstantiationException;
import org.codehaus.plexus.component.repository.ComponentDescriptor;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringOutputStream;
import org.jruby.IRuby;
import org.jruby.Ruby;
import org.jruby.RubyException;
import org.jruby.RubyFixnum;
import org.jruby.RubyGlobal;
import org.jruby.RubyHash;
import org.jruby.RubyIO;
import org.jruby.RubyKernel;
import org.jruby.RubyNil;
import org.jruby.ast.Node;
import org.jruby.exceptions.JumpException;
import org.jruby.exceptions.RaiseException;
import org.jruby.internal.runtime.ValueAccessor;
import org.jruby.javasupport.JavaUtil;
import org.jruby.javasupport.bsf.JRubyEngine;
import org.jruby.parser.ParserSupport;
import org.jruby.runtime.GlobalVariable;
import org.jruby.runtime.IAccessor;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * Configures and invokes the JRuby runtime. The "invoke" method executes a given
 * script (set as a Reader) and returns the results as an IRuby object.
 * 
 * @author eredmond
 */
public class JRubyInvoker
{
    private boolean assumePrintLoop;

    private boolean assumeLoop;

    private boolean autoSplit;

    private boolean processLineEnds;

    private boolean debug;

    private int warning = -1;

    private Map inputs = new HashMap();

    private List libPaths = new LinkedList();

    private List reqLibs = new LinkedList();

    private ClassRealm classRealm;

    private ComponentDescriptor componentDescriptor;

    private Reader reader;

    private IRuby runtime;

    /**
     * Create a reader JRubyInvoker that reads a JRuby script from the given reader.
     * @param classLoader
     */
    public JRubyInvoker( Reader scriptReader )
    {
        this.reader = scriptReader;
    }

    /**
     * Create a JRubyInvoker that runs under the context of this class loader.
     * @param componentDescriptor
     * @param classRealm
     */
    public JRubyInvoker( ComponentDescriptor componentDescriptor, ClassRealm classRealm )
    {
        this.componentDescriptor = componentDescriptor;
        this.classRealm = classRealm;
    }

    /**
     * The sole way of sending a script to this invoker object.
     * If not set, invoker will extract reader from the componentDescriptor.
     * @param reader
     */
    public void setReader( Reader reader )
    {
        this.reader = reader;
    }

    /**
     * Not required, however if set, the invoker will tear down
     * the runtime before exiting Invoke. If you plan on changing the
     * state of inkove-returned IRubyObjects, you should set
     * this externally via Ruby.getDefaultInstance(), and tear it
     * down when done via tearDown() method.
     * @param runtime
     */
    public void setRuntime( IRuby runtime )
    {
        this.runtime = runtime;
    }

    /**
     * As per the Ruby command line arg -n.
     * @param assumeLoop
     */
    public void setAssumeLoop( boolean assumeLoop )
    {
        this.assumeLoop = assumeLoop;
    }

    /**
     * As per the Ruby command line arg -p.
     * @param assumePrintLoop
     */
    public void setAssumePrintLoop( boolean assumePrintLoop )
    {
        this.assumePrintLoop = assumePrintLoop;
    }

    /**
     * As per the Ruby command line arg -a.
     * @param autoSplit
     */
    public void setAutoSplit( boolean autoSplit )
    {
        this.autoSplit = autoSplit;
    }

    /**
     * As per the Ruby command line arg -W#.
     * @param warning
     */
    public void setWarning( int warning )
    {
        this.warning = warning;
    }

    /**
     * As per the Ruby command line arg -d. (sets $DEBUG to true)
     * @param debug
     */
    public void setDebug( boolean debug )
    {
        this.debug = debug;
    }

    /**
     * As per the Ruby command line arg -l.
     * @param processLineEnds
     */
    public void setProcessLineEnds( boolean processLineEnds )
    {
        this.processLineEnds = processLineEnds;
    }

    /**
     * Adds a library as per the Ruby command line arg -I.
     * @param libPath
     */
    public void addLibPath( String libPath )
    {
        this.libPaths.add( libPath );
    }

    /**
     * Adds a 'require' file as per the Ruby command line arg -r.
     * @param reqLib
     */
    public void addReqLib( String reqLib )
    {
        this.reqLibs.add( reqLib );
    }

    /**
     * Appends an input value with the given key to the Ruby
     * script by prepending the following code to the Ruby script:
     *  $INPUT['key'] = value;
     * 
     * @param key
     * @param value
     */
    public void inputValue( String key, Object value )
    {
        this.inputs.put( key, value );
    }

    /**
     * Invokes the script after all other values are set.
     * @return an IRubyObject of returned values
     */
    public IRubyObject invoke()
        throws IOException, ComponentInstantiationException
    {
        return invoke( System.out, System.err );
    }

    /**
     * Invokes the script after all other values are set.
     * @param stdout stream where jruby output to
     * @param stderr stream where jruby errors to
     * @return an IRubyObject of returned values
     */
    public IRubyObject invoke( OutputStream stdout, OutputStream stderr )
        throws IOException, ComponentInstantiationException
    {
        String impl = componentDescriptor.getImplementation();
        if ( !impl.startsWith( "/" ) )
        {
            impl = "/" + impl;
        }

        if ( classRealm != null )
        {
            URL scriptLocation = classRealm.getResource( impl );

            if ( scriptLocation == null )
            {
                StringBuffer buf = new StringBuffer( "Cannot find: " + impl + " in classpath:" );
                for ( int i = 0; i < classRealm.getConstituents().length; i++ )
                {
                    URL constituent = classRealm.getConstituents()[i];
                    buf.append( "\n   [" + i + "]  " + constituent );
                }
                throw new ComponentInstantiationException( buf.toString() );
            }

            reader = new InputStreamReader( scriptLocation.openStream() );
        }
        else if ( reader == null )
        {
            throw new ComponentInstantiationException(
                                                       "If no classRealm is given in the constructor, a script Reader must be set." );
        }
        IRubyObject result = null;
        ClassLoader oldClassLoader = null;
        ClassLoader classLoader = classRealm == null ? null : classRealm.getClassLoader();
        if ( classLoader != null )
        {
            oldClassLoader = Thread.currentThread().getContextClassLoader();
        }

        StringOutputStream bos = null;

        System.setProperty( "jruby.script", "" );
        System.setProperty( "jruby.shell", "/bin/sh" );
        System.setProperty( "jruby.home", "" );
        System.setProperty( "jruby.lib", "/usr/lib/" );

        boolean isExternalRuntime = true;
        if ( runtime == null )
        {
            isExternalRuntime = false;
            runtime = Ruby.getDefaultInstance();
        }

        try
        {
            if ( classLoader != null )
            {
                Thread.currentThread().setContextClassLoader( classLoader );
            }

            bos = new StringOutputStream();

            int read = -1;
            // append the required output streams to the head the script.
            for ( Iterator iter = reqLibs.iterator(); iter.hasNext(); )
            {
                String reqLibPath = (String) iter.next();
                InputStream ris = getFileStream( reqLibPath );
                while ( ( read = ris.read() ) != -1 )
                {
                    bos.write( read );
                }
                ris.close();
            }

            while ( ( read = reader.read() ) != -1 )
            {
                bos.write( read );
            }
            bos.flush();

            if ( debug )
            {
                System.out.println( bos.toString() );
            }

            IRubyObject out = new RubyIO( runtime, stdout );
            IRubyObject err = new RubyIO( runtime, stderr );

            BSFManager manager = new BSFManager();

            try
            {
                for (Iterator iter = inputs.entrySet().iterator(); iter.hasNext(); )
                {
                    Map.Entry entry = (Map.Entry)iter.next();

                    String key = (String)entry.getKey();
                    Object value = entry.getValue();
                    if ( key != null && value != null )
                    {
                        manager.declareBean(key , value, value.getClass() );
                    }
                }

                BSFManager.registerScriptingEngine("ruby", JRubyEngine.class.getName(), new String[] { "rb" });

                manager.declareBean("stdout", out, RubyIO.class);
                manager.declareBean("defout", out, RubyIO.class);
                manager.declareBean(">", out, RubyIO.class);
                manager.declareBean("stderr", err, RubyIO.class);
                manager.declareBean("deferr", err, RubyIO.class);

                result = (IRubyObject)manager.eval( "ruby", "<script>", 1, 1, bos.toString() );
            }
            catch( BSFException e )
            {
                e.printStackTrace();
            }
        }
        finally
        {
            if ( oldClassLoader != null )
            {
                Thread.currentThread().setContextClassLoader( oldClassLoader );
            }

            if ( bos != null )
                bos.close();

            if ( runtime != null )
            {
                if ( result == null )
                    result = RubyNil.createNilClass( runtime );

                // Only tear down the runtime if it was set externally
                if ( !isExternalRuntime )
                {
                    runtime.tearDown();
                }
            }

            IOUtil.close( reader );
        }
        return result;
    }

    private String buildLibs()
    {
        String pathSeperator = System.getProperty( "path.separator" );
        pathSeperator = pathSeperator == null ? ";" : pathSeperator;

        if ( !libPaths.isEmpty() )
        {
            StringBuffer libs = new StringBuffer();
            libs.append( "-I" );
            for ( Iterator iter = libPaths.iterator(); iter.hasNext(); )
            {
                String req = (String) iter.next();
                if ( !"".equals( req ) )
                {
                    libs.append( req );
                    libs.append( pathSeperator );
                }
            }
            return libs.toString();
        }
        return "";
    }

    /** 
     * First search the classrealm... if is does not exist, turn the string to a File 
     */
    private InputStream getFileStream( String resourceName )
        throws ComponentInstantiationException
    {
        InputStream scriptStream = null;
        if ( classRealm != null )
        {
            scriptStream = classRealm.getResourceAsStream( resourceName );
            if ( scriptStream == null )
            {
                File resourceFile = new File( resourceName );
                if ( resourceFile.exists() )
                {
                    try
                    {
                        scriptStream = new FileInputStream( resourceFile );
                    }
                    catch ( FileNotFoundException e )
                    {
                        throw new ComponentInstantiationException( "Volitle file. This should not happen!", e );
                    }
                }
            }
        }
        else
        {
            scriptStream = Thread.currentThread().getContextClassLoader().getResourceAsStream( resourceName );
        }

        if ( scriptStream == null )
        {
            StringBuffer buf = new StringBuffer( "Cannot find: " + resourceName + " in classpath" );
            if ( classRealm != null )
            {
                buf.append( ":" );
                for ( int i = 0; i < classRealm.getConstituents().length; i++ )
                {
                    URL constituent = classRealm.getConstituents()[i];
                    buf.append( "\n   [" + i + "]  " + constituent );
                }
            }
            throw new ComponentInstantiationException( buf.toString() );
        }

        return scriptStream;
    }

    private IRubyObject runInterpreter( IRuby runtime, String script, OutputStream out, OutputStream err )
    {
        try
        {
            initializeRuntime( runtime, out, err );
            Node parsedScript = getParsedScript( runtime, script );
            return runtime.eval( parsedScript );
        }
        catch ( JumpException je )
        {
            if ( je.getJumpType() == JumpException.JumpType.RaiseJump )
            {
                RubyException raisedException = ( (RaiseException) je ).getException();

                if ( raisedException.isKindOf( runtime.getClass( "SystemExit" ) ) )
                {
                    RubyFixnum status = (RubyFixnum) raisedException.getInstanceVariable( "status" );
                    return status;
                }
                else
                {
                    runtime.printError( raisedException );
                    return raisedException;
                }
            }
            else if ( je.getJumpType() == JumpException.JumpType.ThrowJump )
            {
                runtime.printError( (RubyException) je.getTertiaryData() );
                return (RubyException) je.getTertiaryData();
            }
            else
            {
                throw je;
            }
        }
    }

    private Node getParsedScript( IRuby runtime, String script )
    {
        Node result = runtime.parse( script, "<script>" );
        if ( assumePrintLoop )
        {
            result = new ParserSupport().appendPrintToBlock( result );
        }
        if ( assumeLoop )
        {
            result = new ParserSupport().appendWhileLoopToBlock( result, processLineEnds, autoSplit );
        }
        return result;
    }

    // Build script args
    private void initializeRuntime( final IRuby runtime, final OutputStream out, final OutputStream err )
    {
        // new String[]{} are script args... can we pass in script args?
        String[] args = new String[1];
        args[0] = buildLibs();
        IRubyObject argumentArray = runtime.newArray( JavaUtil.convertJavaArrayToRuby( runtime, args ) );
        runtime.setVerbose( runtime.newBoolean( warning == 2 ) );

        IRubyObject stdout = new RubyIO( runtime, out ); //RubyIO.fdOpen(runtime, RubyIO.STDOUT);
        IRubyObject stderr = new RubyIO( runtime, err ); //RubyIO.fdOpen(runtime, RubyIO.STDERR);

        runtime.defineVariable( new OutputGlobalVariable( runtime, "$stdout", stdout ) );
        runtime.defineVariable( new OutputGlobalVariable( runtime, "$stderr", stderr ) );
        runtime.defineVariable( new OutputGlobalVariable( runtime, "$>", stdout ) );
        runtime.defineVariable( new OutputGlobalVariable( runtime, "$defout", stdout ) );
        runtime.defineVariable( new OutputGlobalVariable( runtime, "$deferr", stderr ) );

        runtime.defineGlobalConstant( "STDOUT", stdout );
        runtime.defineGlobalConstant( "STDERR", stderr );

        runtime.getGlobalVariables().define( "$VERBOSE", new IAccessor()
        {
            public IRubyObject getValue()
            {
                return runtime.getVerbose();
            }

            public IRubyObject setValue( IRubyObject newValue )
            {
                if ( newValue.isNil() )
                {
                    runtime.setVerbose( newValue );
                }
                else
                {
                    runtime.setVerbose( runtime.newBoolean( newValue != runtime.getFalse() ) );
                }
                return newValue;
            }
        } );
        runtime.getObject().setConstant( "$VERBOSE", warning == 2 ? runtime.getTrue() : runtime.getNil() );
        runtime.defineGlobalConstant( "ARGV", argumentArray );

        defineGlobal( runtime, "$-p", assumePrintLoop );
        defineGlobal( runtime, "$-n", assumeLoop );
        defineGlobal( runtime, "$-a", autoSplit );
        defineGlobal( runtime, "$-l", processLineEnds );
        runtime.getGlobalVariables().defineReadonly( "$*", new ValueAccessor( argumentArray ) );

        // Inject inputs as a global named "$INPUTS"
        RubyHash globalInputs = new RubyHash( runtime );
        globalInputs.putAll( inputs );
        runtime.getGlobalVariables().set( "$INPUTS", globalInputs );

        // TODO this is a fake cause we have no real process number in Java
        runtime.getGlobalVariables().defineReadonly( "$$", new ValueAccessor( runtime.newFixnum( runtime.hashCode() ) ) );
        runtime.defineVariable( new RubyGlobal.StringGlobalVariable( runtime, "$0", runtime.newString( "<script>" ) ) );
        runtime.getLoadService().init( libPaths );

        Iterator iter = reqLibs.iterator();
        while ( iter.hasNext() )
        {
            String scriptName = (String) iter.next();
            RubyKernel.require( runtime.getTopSelf(), runtime.newString( scriptName ) );
        }
    }

    private void defineGlobal( IRuby runtime, String name, boolean value )
    {
        runtime.getGlobalVariables().defineReadonly( name, new ValueAccessor( value ? runtime.getTrue() : runtime.getNil() ) );
    }

    private static class OutputGlobalVariable
        extends GlobalVariable
    {
        public OutputGlobalVariable( IRuby runtime, String name, IRubyObject value )
        {
            super( runtime, name, value );
        }

        public IRubyObject set( IRubyObject value )
        {
            if ( value == get() )
            {
                return value;
            }
            if ( value instanceof RubyIO )
            {
                if ( !( (RubyIO) value ).isOpen() )
                {
                    throw value.getRuntime().newIOError( "not opened for writing" );
                }
            }
            if ( !value.respondsTo( "write" ) )
            {
                throw runtime
                    .newTypeError( name() + " must have write method, " + value.getType().getName() + " given" );
            }
            return super.set( value );
        }
    }
}
