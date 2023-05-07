/**
 * Copyright 2013-2021 Sylvain Cadilhac (NetFishers)
 * 
 * This file is part of Netshot.
 * 
 * Netshot is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Netshot is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Netshot.  If not, see <http://www.gnu.org/licenses/>.
 */
package onl.netfishers.netshot.compliance.rules;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Transient;
import javax.xml.bind.annotation.XmlElement;

import com.fasterxml.jackson.annotation.JsonView;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import onl.netfishers.netshot.Netshot;
import onl.netfishers.netshot.compliance.CheckResult;
import onl.netfishers.netshot.compliance.Policy;
import onl.netfishers.netshot.compliance.Rule;
import onl.netfishers.netshot.compliance.CheckResult.ResultOption;
import onl.netfishers.netshot.device.Device;
import onl.netfishers.netshot.device.DeviceDriver;
import onl.netfishers.netshot.device.script.helper.PyDeviceHelper;
import onl.netfishers.netshot.device.script.helper.PythonFileSystem;
import onl.netfishers.netshot.rest.RestViews.DefaultView;
import onl.netfishers.netshot.work.TaskLogger;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.hibernate.Session;
import org.slf4j.MarkerFactory;

/**
 * A PythonRule is a Python-coded script that will check the device attributes,
 * including config, and return whether the device is compliant or not.
 */
@Entity
@Slf4j
public class PythonRule extends Rule {

	/** The allowed results. */
	private static CheckResult.ResultOption[] ALLOWED_RESULTS = new CheckResult.ResultOption[] {
			CheckResult.ResultOption.CONFORMING, CheckResult.ResultOption.NONCONFORMING,
			CheckResult.ResultOption.NOTAPPLICABLE, };

	/** Rule loader Python source. */
	private static Source PYLOADER_SOURCE;

	/** Max time (ms) to wait for script to execute */
	private static long MAX_EXECUTION_TIME;

	public static class CachedContext {
		private Context context;
		private long scriptHash;
		public CachedContext(Context context, long scriptHash) {
			this.context = context;
			this.scriptHash = scriptHash;
		}
	}

	/** Cached contexts, indexed by rule ID. */
	private static Map<Long, CachedContext> cachedContexts = new HashMap<>();

	/** Old cached contexts, to be closed. */
	private static List<CachedContext> oldCachedContexts = new ArrayList<>();

	/** The Python execution engine (for eval caching) */
	private static Engine engine = Engine.create();

	/**
	 * Initialize some additional static variables from global configuration.
	 */
	public static void loadConfig() {
		long maxExecutionTime = 60000;
		try {
			maxExecutionTime = Long.parseLong(Netshot.getConfig("netshot.python.maxexecutiontime",
					Long.toString(maxExecutionTime)));
		}
		catch (IllegalArgumentException e) {
			log.error(
				"Invalid value for Python max execution time (netshot.python.maxexecutiontime), using {}ms.",
				maxExecutionTime);
		}
		PythonRule.MAX_EXECUTION_TIME = maxExecutionTime;
	}

	static {
		try {
			log.info("Reading the Python rule loader code from the resource Python file.");
			// Read the JavaScript loader code from the resource file.
			String path = "interfaces/rule-loader.py";
			InputStream in = DeviceDriver.class.getResourceAsStream("/" + path);
			BufferedReader reader = new BufferedReader(new InputStreamReader(in));
			StringBuilder buffer = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				buffer.append(line);
				buffer.append("\n");
			}
			PYLOADER_SOURCE = Source.newBuilder("python", buffer.toString(), "PythonRuleLoader").buildLiteral();
			reader.close();
			in.close();
			log.debug("The Python rule loader code has been read from the resource Python file.");
		}
		catch (Exception e) {
			log.error(MarkerFactory.getMarker("FATAL"), "Unable to read the Python rule loader.", e);
			System.err.println("NETSHOT FATAL ERROR");
			e.printStackTrace();
			System.exit(1);
		}
		PythonRule.loadConfig();
	}

	/** Was the execution prepared? */
	private boolean prepared = false;

	/** Is it a Python-valid rule? */
	private boolean pyValid = false;

	/** The default example script. */
	@Getter(onMethod=@__({
		@XmlElement, @JsonView(DefaultView.class),
		@Column(length = 10000000)
	}))
	@Setter
	private String script = ""
		+ "# Script template - to be customized\n"
		+ "def check(device):\n"
		+ "  ## Grab some data:\n"
		+ "  #  config = device.get('running_config')\n"
		+ "  #  name = device.get('name')\n"
		+ "  ## Some additional checks here...\n"
		+ "  ## debug('device name = %s' % name)\n"
		+ "  return result_option.CONFORMING\n"
		+ "  # return {'result': result_option.NONCONFORMING, 'comment': 'Why it is not fine'}\n"
		+ "  # return result_option.NOTAPPLICABLE\n";

	/** Script as Graal source */
	private Source source = null;

	/**
	 * Instantiates a new Python rule.
	 */
	protected PythonRule() {

	}

	/**
	 * Instantiates a new Python rule.
	 *
	 * @param name
	 *                   the name
	 * @param policy
	 *                   the policy
	 */
	public PythonRule(String name, Policy policy) {
		super(name, policy);
	}

	/**
	 * Prepare the polyglot source.
	 * @return the source
	 */
	@Transient
	protected Source getSource() {
		if (this.source == null) {
			this.source = Source.newBuilder("python", this.script.toString(), "PythonRule" + this.getId()).buildLiteral();
		}
		return this.source;
	}

	/**
	 * Create a GraalVM context and load the rule script.
	 * @return the context
	 * @throws IOException
	 */
	@Transient
	protected Context getContext() throws IOException {
		int scriptHash = this.script.hashCode();
		CachedContext cachedContext = null;
		synchronized (PythonRule.cachedContexts) {
			cachedContext = PythonRule.cachedContexts.get(this.getId());
		}
		if (cachedContext != null && cachedContext.scriptHash == scriptHash) {
			return cachedContext.context;
		}

		Context.Builder builder = Context.newBuilder("python").allowIO(true);
		if ("false".equals(Netshot.getConfig("netshot.python.filesystemfilter"))) {
			log.info("Python VM, file system filter is disabled (this is not secure)");
		}
		else {
			builder.fileSystem(new PythonFileSystem());
		}
		if ("true".equals(Netshot.getConfig("netshot.python.allowallaccess"))) {
			log.info("Python VM, allowing all access (this is not secure)");
			builder.allowAllAccess(true);
		}
		if (PythonFileSystem.VENV_FOLDER != null) {
			builder.allowExperimentalOptions(true)
				.option("python.Executable", PythonFileSystem.VENV_FOLDER + "/bin/graalpython");
		}
		int beforeCacheSize = engine.getCachedSources().size();
		Date beforeTime = new Date();
		Context context;
		synchronized (engine) {
			context = builder.engine(engine).build();
		}
		context.eval(this.getSource());
		context.enter();
		context.eval(PYLOADER_SOURCE);
		Date afterTime = new Date();
		int afterCacheSize = engine.getCachedSources().size();
		log.debug("Python rule {} evalution time {}ms, using {} ({} {}) cached sources",
			this.getId(), afterTime.getTime() - beforeTime.getTime(), afterCacheSize,
			Math.abs(afterCacheSize - beforeCacheSize), afterCacheSize >= beforeCacheSize ? "more" : "less");

		// Add to cached contexts
		synchronized (PythonRule.cachedContexts) {
			PythonRule.cachedContexts.put(this.getId(), new CachedContext(context, scriptHash));
		}

		// Clean up old cached contexts
		synchronized (PythonRule.oldCachedContexts) {
			if (cachedContext != null) {
				oldCachedContexts.add(cachedContext);
			}
			Iterator<CachedContext> oldContextIt = PythonRule.oldCachedContexts.iterator();
			while (oldContextIt.hasNext()) {
				try {
					oldContextIt.next().context.close();
					oldContextIt.remove();
				}
				catch (IllegalStateException e) {
					// continue without removing
				}
			}
		}
		return context;
	}

	/**
	 * Prepare the rule (try to evaluate the script).
	 */
	private void prepare(Context context, TaskLogger taskLogger) {
		if (prepared) {
			return;
		}
		prepared = true;
		pyValid = false;
		
		try {
			Value checkFunction = context.getBindings("python").getMember("check");
			if (checkFunction == null || !checkFunction.canExecute()) {
				log.warn("The check sub wasn't found in the script");
				taskLogger.error("The 'check' sub couldn't be found in the script.");
			}
			else {
				pyValid = true;
			}
		}
		catch (PolyglotException e) {
			taskLogger.error("Error while evaluating the Python script.");
			log.warn("Error while evaluating the Python script.", e);
			pyValid = false;
		}
	}

	/* (non-Javadoc)
	 * @see onl.netfishers.netshot.compliance.Rule#check(onl.netfishers.netshot.device.Device, org.hibernate.Session)
	 */
	@Override
	public CheckResult check(Device device, Session session, TaskLogger taskLogger) {
		if (!this.isEnabled()) {
			return new CheckResult(this, device, ResultOption.DISABLED);
		}

		try {
			Context context = this.getContext();
			prepare(context, taskLogger);
			if (!this.pyValid) {
				return new CheckResult(this, device, ResultOption.INVALIDRULE);
			}
			if (device.isExempted(this)) {
				return new CheckResult(this, device, ResultOption.EXEMPTED);
			}
			PyDeviceHelper deviceHelper = new PyDeviceHelper(device, session, taskLogger, true);
			Future<Value> futureResult = Executors.newSingleThreadExecutor().submit(new Callable<Value>() {
				@Override
				public Value call() throws Exception {
					return context.getBindings("python").getMember("_check").execute(deviceHelper);
				}
			});
			Value result;
			try {
				result = futureResult.get(PythonRule.MAX_EXECUTION_TIME, TimeUnit.MILLISECONDS);
			}
			catch (TimeoutException e1) {
				try {
					context.close(true);
				}
				catch (Exception e2) {
					log.warn("Error while closing abnormally long Python context", e2);
				}
				throw new TimeoutException(
					"The rule took too long to execute (check for endless loop in the script or adjust netshot.python.maxexecutiontime value)");
			}
			String txtResult = null;
			String comment = "";
			if (result.isString()) {
				txtResult = result.asString();
			}
			else if (result.hasHashEntries()) {
				Value jsComment = result.getHashValue("comment");
				if (jsComment != null && jsComment.isString()) {
					comment = jsComment.asString();
				}
				Value jsResult = result.getHashValue("result");
				if (jsResult != null && jsResult.isString()) {
					txtResult = jsResult.asString();
				}
			}
			for (CheckResult.ResultOption allowedResult : ALLOWED_RESULTS) {
				if (allowedResult.toString().equals(txtResult)) {
					taskLogger.info(String.format("The script returned %s (%d), comment '%s'.",
							allowedResult.toString(), allowedResult.getValue(), comment));
					return new CheckResult(this, device, allowedResult, comment);
				}
			}
		}
		catch (IOException e) {
			taskLogger.error("Error while evaluating the Python script.");
			log.warn("Error while evaluating the Python script.", e);
			pyValid = false;
		}
		catch (Exception e) {
			taskLogger.error("Error while running the script: " + e.getMessage());
			log.error("Error while running the script on device {}.", device.getId(), e);
		}
		finally {
			taskLogger.debug("End of check");
		}
		return new CheckResult(this, device, ResultOption.INVALIDRULE);
	}

	@Override
	public String toString() {
		return "Compliance Python-based rule " + id + " (name '" + name + "')";
	}

}
