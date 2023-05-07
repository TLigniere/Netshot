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
package onl.netfishers.netshot.device;

import java.util.List;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Transient;
import javax.xml.bind.annotation.XmlElement;

import com.fasterxml.jackson.annotation.JsonView;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import onl.netfishers.netshot.database.Database;
import onl.netfishers.netshot.device.Finder.Expression.FinderParseException;
import onl.netfishers.netshot.rest.RestViews.DefaultView;

import org.hibernate.HibernateException;
import org.hibernate.query.Query;
import org.hibernate.Session;

/**
 * A dynamic group of devices is defined by search criteria.
 */
@Entity
@Slf4j
public class DynamicDeviceGroup extends DeviceGroup {

	/** The device class. */
	@Getter(onMethod=@__({
		@XmlElement, @JsonView(DefaultView.class)
	}))
	@Setter
	private String driver = null;

	/** The query. */
	@Getter(onMethod=@__({
		@Column(length = 1000),
		@XmlElement, @JsonView(DefaultView.class)
	}))
	@Setter
	private String query = "[Name] IS \"Example\"";

	/**
	 * Instantiates a new dynamic device group.
	 */
	protected DynamicDeviceGroup() {
	}


	public DynamicDeviceGroup(String name, String driver, String query) {
		super(name);
		this.driver = driver;
		this.query = query;
	}

	@Transient
	public DeviceDriver getDeviceDriver() {
		return DeviceDriver.getDriverByName(driver);
	}

	/**
	 * Validate query.
	 *
	 * @return true, if successful
	 */
	@Transient
	public boolean validateQuery() {
		try {
			Finder finder = this.getFinder();
			this.query = finder.getFormattedQuery();
		}
		catch (FinderParseException e) {
			return false;
		}
		return true;
	}

	/**
	 * Gets the finder.
	 *
	 * @return the finder
	 * @throws FinderParseException the finder parse exception
	 */
	@Transient
	private Finder getFinder() throws FinderParseException {
		return new Finder(this.query, this.getDeviceDriver());
	}

	/* (non-Javadoc)
	 * @see onl.netfishers.netshot.device.DeviceGroup#refreshCache(org.hibernate.Session)
	 */
	@Override
	public void refreshCache(Session session) throws FinderParseException, HibernateException {
		Finder finder = this.getFinder();
		Query<Device> cacheQuery = session.createQuery("select d" + finder.getHql(), Device.class);
		finder.setVariables(cacheQuery);
		List<Device> devices = cacheQuery.list();
		this.updateCachedDevices(devices);
		this.query = finder.getFormattedQuery();
	}

	/**
	 * Refresh cache.
	 *
	 * @param session the session
	 * @param device the device
	 * @throws FinderParseException the finder parse exception
	 * @throws HibernateException the hibernate exception
	 */
	public void refreshCache(Session session, Device device) throws FinderParseException, HibernateException {
		log.debug("Refreshing cache of group {} for device {}.", this.getId(), device.getId());
		long deviceId = device.getId();
		if (this.query.isEmpty()) {
			if (this.getDeviceDriver() == null || this.getDeviceDriver().getName().equals(device.getDriver())) {
				this.cachedDevices.add(device);
			}
			else {
				this.cachedDevices.remove(device);
			}
		}
		else {
			String deviceQuery = String.format("[DEVICE] IS %d AND (%s)", deviceId, this.query);
			log.trace("Finder query: '{}'.", deviceQuery);
			Finder finder = new Finder(deviceQuery, this.getDeviceDriver());
			Query<Long> cacheQuery = session.createQuery("select d.id" + finder.getHql(), Long.class);
			finder.setVariables(cacheQuery);
			if (cacheQuery.uniqueResult() == null) {
				this.cachedDevices.remove(device);
			}
			else {
				this.cachedDevices.add(device);
			}
		}
	}

	/**
	 * Refresh all groups.
	 *
	 * @param device the device
	 */
	static synchronized public void refreshAllGroups(Device device) {
		log.debug("Refreshing all groups for device {}.", device.getId());
		Session session = Database.getSession();
		try {
			session.beginTransaction();
			List<DynamicDeviceGroup> groups = session
				.createQuery("select ddg from DynamicDeviceGroup ddg", DynamicDeviceGroup.class)
				.list();
			for (DynamicDeviceGroup group : groups) {
				try {
					group.refreshCache(session, device);
					session.update(group);
				}
				catch (FinderParseException e) {
					log.error("Parse error while updating the group {}.", group.getId(), e);
				}
			}
			session.getTransaction().commit();
		}
		catch (Exception e) {
			session.getTransaction().rollback();
			log.error("Error while refreshing the dynamic groups.", e);
		}
		finally {
			session.close();
		}
	}

}
