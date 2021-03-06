package org.craftyourmod.mineclipse.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

public class MineclipseCore {
	public static final MineclipseCore INSTANCE = new MineclipseCore();
	private File directory, exec;

	private boolean isRunning = false;

	private MineclipseCore() {
	}

	/**
	 * 
	 * @return the dir (cd)
	 */
	public File getDirectory() {
		return directory;
	}

	public void overwrite(final File oldPath, final File newPath,
			final IProgressMonitor monitor) {
		Assert.isTrue(!isRunning, "An instance is already running");
		Assert.isNotNull(oldPath);
		Assert.isNotNull(newPath);
		isRunning = true;
		try {
			copyFolder(oldPath, newPath, "", monitor);
		} catch (IOException e) {
			isRunning = false;
			throw new RuntimeException(e);
		}

		isRunning = false;
	}

	/**
	 * Set the currenty dir
	 */
	public void setDirectory(final File directory) {
		if (!directory.isDirectory())
			throw new IllegalArgumentException("File is not a dir !");
		this.directory = directory;
	}

	/**
	 * 
	 * @return the file to be runned
	 */
	public File getExec() {
		return exec;
	}

	/**
	 * Set the exec location, the file to run
	 * 
	 * @param exec
	 */
	public void setExec(final File exec) {
		if (!exec.isFile())
			throw new IllegalArgumentException("File is not a file !");
		this.exec = exec;
	}

	/**
	 * Run an exec
	 * 
	 * @param monitor
	 *            the {@link IProgressMonitor}
	 * @param writes
	 *            just perm output
	 * @throws InvocationTargetException
	 * @throws InterruptedException
	 * @see #setExec(File)
	 * @see #setDirectory(File)
	 */
	public void run(final IProgressMonitor monitor, final String... writes)
			throws InvocationTargetException, InterruptedException {
		Assert.isTrue(!isRunning);
		isRunning = true;
		Assert.isNotNull(monitor);
		List<String> args = new ArrayList<>();
		args.add(exec.getAbsolutePath());
		ProcessBuilder builder = new ProcessBuilder(args);
		builder.directory(directory);
		Activator
				.getDefault()
				.getLog()
				.log(new Status(Status.INFO, Activator.PLUGIN_ID, "Running "
						+ exec + "\n" + builder));
		MessageConsole myConsole = findConsole("Mineclipse Source Creation");
		final MessageConsoleStream out = myConsole.newMessageStream();
		try {
			final Process process = builder.start();
			new Thread() {
				@Override
				public void run() {
					{
						BufferedWriter writer = new BufferedWriter(
								new OutputStreamWriter(
										process.getOutputStream()));

						while (true) {
							for (String s : writes)
								try {
									try {
										writer.write(s);
										System.out.println("writing");
									} finally {
										writer.close();
									}
								} catch (IOException e) {
								}
							if (monitor.isCanceled()) {
								process.destroy();
								break;
							}
						}
					}

				}
			}.start();
			new Thread() {
				@Override
				public void run() {
					try {
						BufferedReader reader = new BufferedReader(
								new InputStreamReader(process.getInputStream()));
						String line = "";
						try {
							while ((line = reader.readLine()) != null) {
								monitor.subTask("Decompiling - Ouput: " + line);
								System.out.println(line);
								out.write("\n" + line);
								if (monitor.isCanceled())
									process.destroy();
							}
						} finally {
							reader.close();
						}
					} catch (IOException ioe) {
						ioe.printStackTrace();
					}
				}
			}.start();

			new Thread() {
				@Override
				public void run() {
					try {
						BufferedReader reader = new BufferedReader(
								new InputStreamReader(process.getErrorStream()));
						String line = "";
						try {
							while ((line = reader.readLine()) != null) {
								monitor.subTask("Decompiling - Ouput: " + line);
								System.out.println(line);
								out.write("\n" + line);
								if (monitor.isCanceled())
									process.destroy();
							}

						} finally {
							reader.close();
						}
					} catch (IOException ioe) {
						ioe.printStackTrace();
					}
				}
			}.start();
			process.waitFor();
		} catch (IOException e) {
			Activator
					.getDefault()
					.getLog()
					.log(new Status(Status.ERROR, Activator.PLUGIN_ID,
							"Cannot exec " + exec + "\nBecause "
									+ e.getMessage()));
			isRunning = false;
		}
		isRunning = false;

	}

	private static MessageConsole findConsole(final String name) {
		ConsolePlugin plugin = ConsolePlugin.getDefault();
		IConsoleManager conMan = plugin.getConsoleManager();
		IConsole[] existing = conMan.getConsoles();
		for (IConsole element : existing)
			if (name.equals(element.getName()))
				return (MessageConsole) element;
		// no console found, so create a new one
		MessageConsole myConsole = new MessageConsole(name, null);
		conMan.addConsoles(new IConsole[] { myConsole });
		return myConsole;
	}

	/**
	 * Copy the files using NIO
	 * 
	 * @param input
	 *            the source directory
	 * @param output
	 *            the target directory
	 * @param exclude
	 *            the pattern to include some files, put null or empty strings
	 *            to ignore
	 * @param monitor
	 *            the {@link IProgressMonitor} to be used for a progress manager
	 *            (optional)
	 */
	public void performCopy(final File input, final File output,

	final String include, final IProgressMonitor monitor) {

		// Checking
		Assert.isNotNull(input);
		Assert.isNotNull(output);
		Assert.isTrue(!isRunning);
		Assert.isTrue(input.exists());
		output.mkdirs();
		Assert.isTrue(output.exists());
		// Copy
		isRunning = true;
		try {
			copyFolder(input, output, include, monitor);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {

			isRunning = false;
		}
	}

	/*
	 * code from Mkyong.com
	 */

	private void copyFolder(final File src, final File dest,
			final String include, final IProgressMonitor monitor)
			throws IOException {
		try {
			if (monitor.isCanceled())
				return;
		} catch (Exception e) {
		}
		if (src.isDirectory()) {

			// if directory not exists, create it
			if (!dest.exists())
				dest.mkdir();

			// list all the directory contents
			String files[] = src.list(new FilenameFilter() {

				@Override
				public boolean accept(final File file, final String name) {
					if ((include == null) || include.isEmpty())
						return true;
					if (name.matches(include))
						return true;
					return false;
				}
			});

			for (String file : files) {
				// construct the src and dest file structure
				File srcFile = new File(src, file);
				File destFile = new File(dest, file);
				// recursive copy
				copyFolder(srcFile, destFile, include, monitor);
			}

		} else {
			// if file, then copy it
			// Use bytes stream to support all file types
			FileInputStream fIn;
			FileOutputStream fOut;
			FileChannel fIChan, fOChan;
			long fSize;
			MappedByteBuffer mBuf;
			if (monitor != null)
				monitor.subTask("Copying file : " + src);
			try {
				fIn = new FileInputStream(src);
				fOut = new FileOutputStream(dest);

				fIChan = fIn.getChannel();
				fOChan = fOut.getChannel();

				fSize = fIChan.size();

				mBuf = fIChan.map(FileChannel.MapMode.READ_ONLY, 0, fSize);

				fOChan.write(mBuf); // this copies the file

				fIChan.close();
				fIn.close();

				fOChan.close();
				fOut.close();
			} catch (IOException exc) {
				throw new RuntimeException(exc);
			} catch (ArrayIndexOutOfBoundsException exc) {
				throw new RuntimeException(exc);
			}

		}

	}
}
