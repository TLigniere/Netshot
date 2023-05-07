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
package onl.netfishers.netshot.compliance;

import java.util.regex.PatternSyntaxException;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.Transient;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonView;

import lombok.Getter;
import lombok.Setter;
import onl.netfishers.netshot.device.Device;
import onl.netfishers.netshot.device.DeviceDriver;
import onl.netfishers.netshot.device.DeviceGroup;
import onl.netfishers.netshot.device.Module;
import onl.netfishers.netshot.rest.RestViews.DefaultView;

/**
 * A software rule defines constraints that apply to the software versions.
 */
@Entity
@XmlRootElement @XmlAccessorType(value = XmlAccessType.NONE)
public class SoftwareRule implements Comparable<SoftwareRule> {

	/**
	 * The Enum ConformanceLevel.
	 */
	public static enum ConformanceLevel {

		/** The gold. */
		GOLD,

		/** The silver. */
		SILVER,

		/** The bronze. */
		BRONZE,

		/** The unknown. */
		UNKNOWN
	}

	/** The id. */
	@Getter(onMethod=@__({
		@Id, @GeneratedValue(strategy = GenerationType.IDENTITY),
		@XmlAttribute, @JsonView(DefaultView.class)
	}))
	@Setter
	protected long id;

	/** The priority, to determine which software rule should apply first. */
	@Getter(onMethod=@__({
		@XmlElement, @JsonView(DefaultView.class)
	}))
	@Setter
	protected double priority;

	/** The target group. */
	@Getter(onMethod=@__({
		@ManyToOne,
		@XmlElement, @JsonView(DefaultView.class)
	}))
	@Setter
	protected DeviceGroup targetGroup;

	@Getter(onMethod=@__({
		@XmlElement, @JsonView(DefaultView.class)
	}))
	@Setter
	private String driver = null;

	/** The family. */
	@Getter(onMethod=@__({
		@XmlElement, @JsonView(DefaultView.class)
	}))
	@Setter
	private String family = "";

	/** The family is a regular expression. */
	@Getter(onMethod=@__({
		@XmlElement, @JsonView(DefaultView.class)
	}))
	@Setter
	private boolean familyRegExp = false;

	/** The version. */
	@Getter(onMethod=@__({
		@XmlElement, @JsonView(DefaultView.class)
	}))
	@Setter
	private String version = "";

	/** The version is a regular expression. */
	@Getter(onMethod=@__({
		@XmlElement, @JsonView(DefaultView.class)
	}))
	@Setter
	private boolean versionRegExp = false;

	/** The part number. */
	@Getter(onMethod=@__({
		@XmlElement, @JsonView(DefaultView.class)
	}))
	@Setter
	private String partNumber;

	/** The part number reg exp. */
	@Getter(onMethod=@__({
		@XmlElement, @JsonView(DefaultView.class)
	}))
	@Setter
	private boolean partNumberRegExp = false;

	/** The level. */
	@Getter(onMethod=@__({
		@XmlElement, @JsonView(DefaultView.class)
	}))
	@Setter
	protected ConformanceLevel level = ConformanceLevel.GOLD;


	/**
	 * Instantiates a new software rule.
	 */
	protected SoftwareRule() {

	}

	public SoftwareRule(double priority, DeviceGroup targetGroup,
			String driver, String family, boolean familyRegExp,
			String version, boolean versionRegExp, String partNumber,
			boolean partNumberRegExp, ConformanceLevel level) {
		this.priority = priority;
		this.targetGroup = targetGroup;
		this.driver = driver;
		this.family = family;
		this.familyRegExp = familyRegExp;
		this.version = version;
		this.versionRegExp = versionRegExp;
		this.partNumber = partNumber;
		this.partNumberRegExp = partNumberRegExp;
		this.level = level;
	}

	/* (non-Javadoc)
	 * @see java.lang.Comparable#compareTo(java.lang.Object)
	 */
	@Override
	public int compareTo(SoftwareRule o) {
		return Double.compare(this.priority, o.priority);
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (int) (id ^ (id >>> 32));
		return result;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		SoftwareRule other = (SoftwareRule) obj;
		return id == other.id;
	}

	/**
	 * Gets the device type.
	 *
	 * @return the device type
	 */
	@Transient
	@XmlElement @JsonView(DefaultView.class)
	public String getDeviceType() {
		DeviceDriver deviceDriver = DeviceDriver.getDriverByName(driver);
		if (deviceDriver == null) {
			return "";
		}
		return deviceDriver.getDescription();
	}

	/**
	 * Check.
	 *
	 * @param device the device
	 */
	public void check(Device device) {
		device.setSoftwareLevel(ConformanceLevel.UNKNOWN);
		if (targetGroup != null && !targetGroup.getCachedDevices().contains(device)) {
			return;
		}
		if (driver != null && !driver.equals(device.getDriver())) {
			return;
		}
		if (familyRegExp) {
			try {
				if (!device.getFamily().matches(family)) {
					return;
				}
			}
			catch (PatternSyntaxException e) {
				return;
			}
		}
		else {
			if (!family.equals("") && !device.getFamily().equals(family)) {
				return;
			}
		}
		if (versionRegExp) {
			try {
				if (!device.getSoftwareVersion().matches(version)) {
					return;
				}
			}
			catch (PatternSyntaxException e) {
				return;
			}
		}
		else {
			if (!version.equals("") && !device.getSoftwareVersion().equals(version)) {
				return;
			}
		}
		if (partNumber != null && !partNumber.equals("")) {
			boolean moduleMatches = false;
			for (Module module : device.getModules()) {
				if (partNumberRegExp) {
					try {
						if (!module.getPartNumber().matches(partNumber)) {
							continue;
						}
					}
					catch (PatternSyntaxException e) {
						return;
					}
				}
				else {
					if (!partNumber.equals("") && !module.getPartNumber().equals(partNumber)) {
						continue;
					}
				}
				moduleMatches = true;
				break;
			}
			if (!moduleMatches) {
				return;
			}
		}
		device.setSoftwareLevel(this.level);
	}

	@Override
	public String toString() {
		return "Software compliance rule " + id;
	}

}
