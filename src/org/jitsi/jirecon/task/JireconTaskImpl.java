/*
 * Jirecon, the Jitsi recorder container.
 * 
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.jitsi.jirecon.task;

import java.io.File;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import org.jitsi.jirecon.JireconEvent;
import org.jitsi.jirecon.JireconEventListener;
import org.jitsi.jirecon.dtlscontrol.*;
import org.jitsi.jirecon.task.recorder.*;
import org.jitsi.jirecon.task.session.*;
import org.jitsi.jirecon.transport.*;
import org.jitsi.jirecon.utils.*;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.libjitsi.LibJitsi;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.format.MediaFormat;
import org.jitsi.util.Logger;
import org.jivesoftware.smack.*;

/**
 * An implementation of <tt>JireconTask</tt>. It is designed in Mediator
 * pattern, <tt>JireconTaskImpl</tt> is the mediator, others like
 * <tt>JireconSession</tt>, <tt>JireconRecorder</tt> are the colleagues.
 * 
 * @author lishunyang
 * @see JireconTask
 * 
 */
public class JireconTaskImpl
    implements JireconTask, JireconEventListener, JireconTaskEventListener,
    Runnable
{
    /**
     * The <tt>JireconEvent</tt> listeners, they will be notified when some
     * important things happen.
     */
    private List<JireconEventListener> listeners =
        new ArrayList<JireconEventListener>();

    /**
     * The instance of <tt>JireconSession</tt>.
     */
    private JireconSession session;

    /**
     * The instance of <tt>JireconTransportManager</tt>.
     */
    private JireconTransportManager transport;

    /**
     * The instance of <tt>SrtpControlManager</tt>.
     */
    private SrtpControlManager srtpControl;

    /**
     * The instance of <tt>JireconRecorder</tt>.
     */
    private JireconRecorder recorder;

    /**
     * The thread pool to make the method "start" to be asynchronous.
     */
    private ExecutorService executorService;

    /**
     * Indicate whether this task has stopped or not.
     */
    private boolean isStopped = false;

    /**
     * Record the task info. <tt>JireconTaskInfo</tt> can be accessed by outside
     * system.
     */
    private JireconTaskInfo info = new JireconTaskInfo();

    /**
     * The <tt>Logger</tt>, used to log messages to standard output.
     */
    private static final Logger logger = Logger
        .getLogger(JireconTaskImpl.class);

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(String mucJid, XMPPConnection connection, String savingDir)
    {
        logger.setLevelAll();
        logger.debug(this.getClass() + " init");

        ConfigurationService configuration = LibJitsi.getConfigurationService();

        info.setMucJid(mucJid);
        info.setNickname(configuration
            .getString(JireconConfigurationKey.NICK_KEY));

        executorService =
            Executors.newSingleThreadExecutor(new HandlerThreadFactory());

        File dir = new File(savingDir);
        if (!dir.exists())
        {
            dir.mkdirs();
        }

        transport = new JireconIceUdpTransportManagerImpl();

        srtpControl = new DtlsControlManagerImpl();
        srtpControl.setHashFunction(configuration
            .getString(JireconConfigurationKey.HASH_FUNCTION_KEY));

        session = new JireconSessionImpl();
        session.addTaskEventListener(this);
        session.init(connection);

        recorder = new JireconRecorderImpl();
        recorder.addTaskEventListener(this);
        recorder.init(savingDir, srtpControl.getAllSrtpControl());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void uninit()
    {
        // Stop the task in case of something hasn't been released correctly.
        stop();
        info = new JireconTaskInfo();
        listeners.clear();
        transport.free();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start()
    {
        executorService.execute(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop()
    {
        if (!isStopped)
        {
            logger.info(this.getClass() + " stop.");
            recorder.stopRecording();
            session.disconnect(Reason.SUCCESS, "OK, gotta go.");
            isStopped = true;
        }
    }

    /**
     * This is actually the main part of method "start", in order to make the
     * method "start" to be asynchronous.
     * <p>
     * 1. Harvest local candidates.
     * <p>
     * 2. Connect with MUC.
     * <p>
     * 3. Build ICE connectivity.
     * <p>
     * 4. Start recording.
     * <p>
     * <strong>Warning:</strong> In execution flow above, step 2, 3, 4 are
     * actually overlapped instead of sequential.
     */
    @Override
    public void run()
    {
        try
        {
            // Harvest local candidates. This should be done first because we
            // need those information when build Jingle session.
            transport.harvestLocalCandidates();

            // Prepare the local ssrcs to create Jingle packet.
            session.setLocalSsrcs(recorder.getLocalSsrcs());

            // Build the Jingle session with specified MUC.
            JingleIQ initIq =
                session.connect(transport, srtpControl, info.getMucJid(),
                    info.getNickname());

            // Parse remote fingerprint from Jingle session-init packet and
            // setup srtp control manager.
            Map<MediaType, String> fingerprints =
                JinglePacketParser.getFingerprint(initIq);
            for (Entry<MediaType, String> f : fingerprints.entrySet())
            {
                srtpControl.addRemoteFingerprint(f.getKey(), f.getValue());
            }

            // Parse remote candidates information from Jingle session-init
            // packet and setup transport manager.
            Map<MediaType, IceUdpTransportPacketExtension> transportPEs =
                JinglePacketParser.getTransportPacketExts(initIq);
            transport.harvestRemoteCandidates(transportPEs);

            // Start establishing ICE connectivity. Notice that this method is
            // asynchronous method.
            transport.startConnectivityEstablishment();

            // Once transport manager has selected candidates pairs, get stream
            // connectors. Notice that we have to wait for at least one
            // candidate pair being selected. If ICE connectivity establishment
            // doesn't get selected pairs for a long time, break the task.
            Map<MediaType, StreamConnector> streamConnectors =
                new HashMap<MediaType, StreamConnector>();
            Map<MediaType, MediaStreamTarget> mediaStreamTargets =
                new HashMap<MediaType, MediaStreamTarget>();
            for (MediaType mediaType : MediaType.values())
            {
                if (mediaType != MediaType.AUDIO
                    && mediaType != MediaType.VIDEO)
                    continue;

                StreamConnector streamConnector =
                    transport.getStreamConnector(mediaType);
                streamConnectors.put(mediaType, streamConnector);

                MediaStreamTarget mediaStreamTarget =
                    transport.getStreamTarget(mediaType);
                mediaStreamTargets.put(mediaType, mediaStreamTarget);
            }

            // Gather some information and start recording.
            Map<MediaFormat, Byte> formatAndDynamicPTs =
                session.getFormatAndPayloadType();
            recorder.startRecording(formatAndDynamicPTs, streamConnectors,
                mediaStreamTargets);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            fireEvent(new JireconEvent(this, JireconEvent.Type.TASK_ABORTED));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addEventListener(JireconEventListener listener)
    {
        listeners.add(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeEventListener(JireconEventListener listener)
    {
        listeners.remove(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JireconTaskInfo getTaskInfo()
    {
        return info;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleEvent(JireconEvent evt)
    {
        for (JireconEventListener l : listeners)
        {
            l.handleEvent(evt);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleTaskEvent(JireconTaskEvent event)
    {
        System.out.println(event);

        if (event.getType() == JireconTaskEvent.Type.PARTICIPANT_CAME)
        {
            Map<String, List<String>> associatedSsrcs =
                session.getAssociatedSsrcs();
            recorder.setAssociatedSsrcs(associatedSsrcs);
            return;
        }

        if (event.getType() == JireconTaskEvent.Type.PARTICIPANT_LEFT)
        {
            // TODO: I should do something in case of any participant left the
            // MUC.
            return;
        }
    }

    /**
     * Fire the event if the task has finished or break.
     * 
     * @param evt is the <tt>JireconEvent</tt> you want to notify the listeners.
     */
    private void fireEvent(JireconEvent evt)
    {
        for (JireconEventListener l : listeners)
        {
            l.handleEvent(evt);
        }
    }

    /**
     * Thread exception handler, in order to catch exceptions of the task
     * thread.
     * 
     * @author lishunyang
     * 
     */
    private class ThreadExceptionHandler
        implements Thread.UncaughtExceptionHandler
    {
        @Override
        public void uncaughtException(Thread t, Throwable e)
        {
            if (t instanceof JireconTask)
            {
                ((JireconTask) e).stop();
                fireEvent(new JireconEvent(JireconTaskImpl.this,
                    JireconEvent.Type.TASK_ABORTED));
            }
        }
    }

    /**
     * Handler factory, in order to create thread with
     * <tt>ThreadExceptionHandler</tt>
     * 
     * @author lishunyang
     * 
     */
    private class HandlerThreadFactory
        implements ThreadFactory
    {
        @Override
        public Thread newThread(Runnable r)
        {
            System.out.println(this + " creating new Thread");
            Thread t = new Thread(r);
            t.setUncaughtExceptionHandler(new ThreadExceptionHandler());
            return t;
        }
    }
}
