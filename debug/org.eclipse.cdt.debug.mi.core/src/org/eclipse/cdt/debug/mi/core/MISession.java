/*
 * (c) Copyright QNX Software Systems Ltd. 2002.
 * All Rights Reserved.
 */
package org.eclipse.cdt.debug.mi.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Observable;

import org.eclipse.cdt.debug.mi.core.command.Command;
import org.eclipse.cdt.debug.mi.core.command.CommandFactory;
import org.eclipse.cdt.debug.mi.core.command.MIExecAbort;
import org.eclipse.cdt.debug.mi.core.command.MIExecInterrupt;
import org.eclipse.cdt.debug.mi.core.command.MIGDBExit;
import org.eclipse.cdt.debug.mi.core.command.MIGDBSet;
import org.eclipse.cdt.debug.mi.core.output.MIInfo;
import org.eclipse.cdt.debug.mi.core.output.MIOutput;
import org.eclipse.cdt.debug.mi.core.output.MIParser;

/**
 * Represents a GDB/MI session.
 * Note that on GNU/Linux the target stream is not
 * preceded by the token '@' until this is fix, on GNU/Linux
 * there a good change to confuse the parser.
 */
public class MISession extends Observable {

	InputStream inChannel;
	OutputStream outChannel;

	TxThread txThread;
	RxThread rxThread;
	EventThread eventThread;

	CommandQueue txQueue;
	CommandQueue rxQueue;
	Queue eventQueue;

	PipedInputStream miInPipe;
	PipedOutputStream miOutPipe;

	CommandFactory factory;

	MIParser parser;

	long cmdTimeout = 10000; // 10 * 1000 (~ 10 secs);

	MIInferior inferior;

	/**
	 * Create the gdb session.
	 *
	 * @param i the gdb input channel.
	 * @param o gdb output channel.
	 */
	public MISession(InputStream i, OutputStream o) {
		inChannel = i;
		outChannel = o;

		factory = new CommandFactory();

		parser = new MIParser();

		txQueue = new CommandQueue();
		rxQueue = new CommandQueue();
		eventQueue = new Queue();

		txThread = new TxThread(this);
		rxThread = new RxThread(this);
		eventThread = new EventThread(this);

		txThread.start();
		rxThread.start();
		eventThread.start();

		inferior = new MIInferior(this);
		try {
			// Disable a certain number of irritations from gdb.
			// Like confirmation and screen size.
			MIInfo info;

			MIGDBSet confirm = new MIGDBSet(new String[]{"confirm", "off"});
			postCommand(confirm);
			info = confirm.getMIInfo(); 

			MIGDBSet width = new MIGDBSet(new String[]{"width", "0"});
			postCommand(width);
			info = confirm.getMIInfo(); 

			MIGDBSet height = new MIGDBSet(new String[]{"height", "0"});
			postCommand(height);
			info = confirm.getMIInfo(); 
		} catch (MIException e) {
			// FIXME: Do not catch the exception but pass it up.
		}
	}

	/**
	 * get MI Console Stream.
	 */
	public InputStream getMIStream() {
		if (miInPipe == null) {
			try {
				miOutPipe = new PipedOutputStream();
				miInPipe = new PipedInputStream(miOutPipe);
			} catch (IOException e) {
			}
		}
		return miInPipe;
	}


	/**
	 * For example the CDI/MI bridge uses the command
	 * factory to create MI commands this allow overloading.
	 */
	public CommandFactory getCommandFactory() {
		return factory;
	}

	/**
	 * Set a new factory to use for command.
	 */
	public void setCommandFactory(CommandFactory f) {
		factory = f;
	}

	/**
	 * Return the MI parser.
	 */
	public MIParser getMIParser() {
		return parser;
	}

	/**
	 * Reset the MI parser.
	 */
	public void setMIParser(MIParser p) {
		parser = p;
	}

	/**
	 * Reset the default Command Timeout.
	 */
	public void setCommandTimeout(long timeout) {
		cmdTimeout = timeout;
	}

	/**
	 * Return the default Command Timeout, default 10 secs.
	 */
	public long getCommandTimeout() {
		return cmdTimeout;
	}

	/**
	 * equivalent to:
	 * postCommand(cmd, 10 secs) 
	 */
	public void postCommand(Command cmd) throws MIException {
		postCommand(cmd, cmdTimeout);
	}

	/**
	 * Sends a command to gdb, and wait(timeout) for a response.
	 */
	static int number = 1;
	public synchronized void postCommand(Command cmd, long timeout) throws MIException {

MIPlugin.getDefault().debugLog(number++ + " " + cmd.toString());

		// Test if we are in a sane state.
		if (!txThread.isAlive() || !rxThread.isAlive()) {
			throw new MIException("{R,T}xThread terminated");
		}

		// Test if we are in the right state?
		if (inferior.isRunning()) {
			if ( !((cmd instanceof MIExecInterrupt) ||
					cmd instanceof MIExecAbort)) {
				throw new MIException("Target running");
			}
		}

		txQueue.addCommand(cmd);

		// Wait for the response or timedout
		synchronized (cmd) {
			// RxThread will set the MIOutput on the cmd
			// when the response arrive.
			while (cmd.getMIOutput() == null) {
				try {
					cmd.wait(timeout);
					if (cmd.getMIOutput() == null) {
						throw new MIException("Timedout");
					}
				} catch (InterruptedException e) {
				}
			}
		}
	}

	/**
	 * Return the inferior "Process".
	 */
	public MIInferior getMIInferior() {
		return inferior;
	}

	/**
	 * Check if the gdb session is terminated.
	 */
	public boolean isTerminated() {
		return (!txThread.isAlive() || !rxThread.isAlive());
	}
	
	/**
	 * Terminate the MISession.
	 */
	public void terminate() {

		// Destroy any MI Inferior(Process)
		inferior.destroy();

		// send the exit(-gdb-exit).
		try {
			MIGDBExit exit = factory.createMIGDBExit();
			postCommand(exit);
		} catch (MIException e) {
		}

		// Close the input GDB prompt
		try {
			inChannel.close();
		} catch (IOException e) {
		}
		inChannel = null;

		// Close the output GDB prompt
		try {
			outChannel.close();
		} catch (IOException e) {
		}
		// This is __needed__ to stop the txThread and eventThread.
		outChannel = null;
		
		// Kill the Transmition thread.
		try {
			if (txThread.isAlive()) {
				txThread.interrupt();
			}
			txThread.join(cmdTimeout);
		} catch (InterruptedException e) {
		}
		
		// Kill the Receiving Thread.
		try {
			if (rxThread.isAlive()) {
				rxThread.interrupt();
			}
			rxThread.join(cmdTimeout);
		} catch (InterruptedException e) {
		}

		// Kill the event Thread.
		try {
			if (eventThread.isAlive()) {
				eventThread.interrupt();
			}
			eventThread.join(cmdTimeout);
		} catch (InterruptedException e) {
		}

		// Destroy the MI console stream.
		try {
			miOutPipe.close();
			miInPipe = null;
		} catch (IOException e) {
		}
	}

	/**
	 * Notify the observers of new MI OOB events.
	 */
	public void notifyObservers(Object arg) {
		setChanged();
		super.notifyObservers(arg);
	}


	OutputStream getConsolePipe() {
		return miOutPipe;
	}

	CommandQueue getTxQueue() {
		return txQueue;
	}

	CommandQueue getRxQueue() {
		return rxQueue;
	}

	Queue getEventQueue() {
		return eventQueue;
	}

	RxThread getRxThread() {
		return rxThread;
	}

	InputStream getChannelInputStream() {
		return inChannel;
	}

	OutputStream getChannelOutputStream() {
		return outChannel;
	}

	MIOutput parse(String buffer) {
		return parser.parse(buffer);
	}
}
