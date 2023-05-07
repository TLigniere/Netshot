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
package onl.netfishers.netshot.work.tasks;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.ManyToOne;
import javax.persistence.Transient;
import javax.xml.bind.annotation.XmlElement;

import com.fasterxml.jackson.annotation.JsonView;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import onl.netfishers.netshot.database.Database;
import onl.netfishers.netshot.device.Device;
import onl.netfishers.netshot.device.DeviceDriver;
import onl.netfishers.netshot.device.script.JsCliScript;
import onl.netfishers.netshot.rest.RestViews.DefaultView;
import onl.netfishers.netshot.work.Task;

import org.hibernate.Hibernate;
import org.hibernate.Session;
import org.quartz.JobKey;

/**
 * This task runs a JS script on a device.
 */
@Entity
@Slf4j
public class RunDeviceScriptTask extends Task implements DeviceBasedTask {

	/** The device. */
	@Getter(onMethod=@__({
		@ManyToOne(fetch = FetchType.LAZY)
	}))
	@Setter
	private Device device;
	
	@Getter(onMethod=@__({
		@Column(length = 10000000)
	}))
	@Setter
	private String script;
	
	@Getter
	@Setter
	private String deviceDriver;

	/**
	 * Instantiates a new take snapshot task.
	 */
	protected RunDeviceScriptTask() {
	}

	/**
	 * Instantiates a new take snapshot task.
	 *
	 * @param device the device
	 * @param comments the comments
	 */
	public RunDeviceScriptTask(Device device, String script, DeviceDriver driver, String comments, String author) {
		super(comments, (device.getLastConfig() == null ? device.getMgmtAddress().getIp() : device.getName()),
				author);
		this.device = device;
		this.script = script;
		this.deviceDriver = driver.getName();
	}

	@Override
	public void prepare() {
		Hibernate.initialize(this.getDevice());
	}


	@Override
	public void run() {
		log.debug("Task {}. Starting script task for device {}.", this.getId(), device.getId());
		this.info(String.format("Run script task for device %s (%s).",
				device.getName(), device.getMgmtAddress().getIp()));

		JsCliScript cliScript = null;
		Session session = Database.getSession();
		try {
			session.beginTransaction();
			session.refresh(device);
			if (deviceDriver == null || !deviceDriver.equals(device.getDriver())) {
				log.trace("Task {}. The script doesn't apply to the driver of the device.", this.getId());
				this.error("The script doesn't apply to the driver of the device.");
				this.status = Status.CANCELLED;
				return;
			}
			if (device.getStatus() != Device.Status.INPRODUCTION) {
				log.trace("Task {}. Device not INPRODUCTION, stopping the run script task.", this.getId());
				this.warn("The device is not enabled (not in production).");
				this.status = Status.FAILURE;
				return;
			}
			
			cliScript = new JsCliScript(script, false);
			cliScript.connectRun(session, device);
			
			this.info(String.format("Device logs (%d next lines):", cliScript.getJsLog().size()));
			this.logs.append(cliScript.getPlainJsLog());
			session.update(device);
			session.getTransaction().commit();
			this.status = Status.SUCCESS;
		}
		catch (Exception e) {
			try {
				session.getTransaction().rollback();
			}
			catch (Exception e1) {
				log.error("Task {}. Error during transaction rollback.", this.getId(), e1);
			}
			log.error("Task {}. Error while running the script.", this.getId(), e);
			this.error("Error while running the script: " + e.getMessage());
			if (cliScript != null) {
				this.info(String.format("Device logs (%d next lines):", cliScript.getJsLog().size()));
				this.logs.append(cliScript.getPlainJsLog());
			}
			
			this.status = Status.FAILURE;
			return;
		}
		finally {
			session.close();
		}
	}

	/* (non-Javadoc)
	 * @see onl.netfishers.netshot.work.Task#getTaskDescription()
	 */
	@Override
	@XmlElement @JsonView(DefaultView.class)
	@Transient
	public String getTaskDescription() {
		return "Device script execution";
	}

	/* (non-Javadoc)
	 * @see onl.netfishers.netshot.work.Task#clone()
	 */
	@Override
	public Object clone() throws CloneNotSupportedException {
		RunDeviceScriptTask task = (RunDeviceScriptTask) super.clone();
		task.setDevice(this.device);
		return task;
	}

	/*
	 * (non-Javadoc)
	 * @see onl.netfishers.netshot.work.Task#getIdentity()
	 */
	@Override
	@Transient
	public JobKey getIdentity() {
		return new JobKey(String.format("Task_%d", this.getId()), 
				String.format("RunDevice_%d", this.getDevice().getId()));
	}
}
