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

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Embeddable;
import javax.persistence.EmbeddedId;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.ManyToOne;
import javax.persistence.Transient;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonView;

import lombok.Getter;
import lombok.Setter;

import org.apache.commons.lang3.StringUtils;

import onl.netfishers.netshot.device.Device;
import onl.netfishers.netshot.rest.RestViews.DefaultView;

/**
 * A CheckResult object is the result of a compliance test for a given rule,
 * tested against a given device.
 */
@Entity
@XmlRootElement @XmlAccessorType(value = XmlAccessType.NONE)
public class CheckResult {

	/**
	 * The Class Key.
	 */
	@Embeddable
	public static class Key implements Serializable {

		/** The Constant serialVersionUID. */
		private static final long serialVersionUID = 8277228096576043644L;

		@Getter(onMethod=@__({
			@ManyToOne
		}))
		@Setter
		private Rule rule = null;

		/** The device. */
		@Getter(onMethod=@__({
			@ManyToOne
		}))
		@Setter
		private Device device = null;

		/**
		 * Instantiates a new key.
		 */
		protected Key() {

		}

		/**
		 * Instantiates a new key.
		 *
		 * @param rule the rule
		 * @param device the device
		 */
		public Key(Rule rule, Device device) {
			this.rule = rule;
			this.device = device;
		}

		/* (non-Javadoc)
		 * @see java.lang.Object#hashCode()
		 */
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((device == null) ? 0 : device.hashCode());
			result = prime * result + ((rule == null) ? 0 : rule.hashCode());
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
			Key other = (Key) obj;
			if (device == null) {
				if (other.device != null)
					return false;
			}
			else if (!device.equals(other.device))
				return false;
			if (rule == null) {
				if (other.rule != null)
					return false;
			}
			else if (!rule.equals(other.rule))
				return false;
			return true;
		}

	}

	/**
	 * The Enum ResultOption.
	 */
	public static enum ResultOption {

		/** The device is confirming to the rule. */
		CONFORMING(0),

		/** The device is exempted from this rule. */
		EXEMPTED(4),

		/** The rule is invalid. */
		INVALIDRULE(3),

		/** The rule is disabled. */
		DISABLED(5),

		/** The device isn't conforming to the rule. */
		NONCONFORMING(1),

		/** The rule is not applicable to this device. */
		NOTAPPLICABLE(2);

		/** The value. */
		@Getter
		final private int value;

		/**
		 * Instantiates a new result option.
		 *
		 * @param value the value
		 */
		private ResultOption(int value) {
			this.value = value;
		}
	}

	/** The key. */
	@Getter(onMethod=@__({
		@EmbeddedId
	}))
	@Setter
	private Key key = new Key();

	/** The check date. */
	@Getter(onMethod=@__({
		@XmlElement, @JsonView(DefaultView.class)
	}))
	@Setter
	private Date checkDate = new Date();

	/** The comment. */
	@Getter(onMethod=@__({
		@XmlElement, @JsonView(DefaultView.class),
		@Column(length = 10000)
	}))
	private String comment = "";

	/** The result. */
	@Getter(onMethod=@__({
		@XmlElement, @JsonView(DefaultView.class)
	}))
	@Setter
	private CheckResult.ResultOption result = CheckResult.ResultOption.NOTAPPLICABLE;

	/**
	 * Instantiates a new check result.
	 */
	protected CheckResult() {
	}

	/**
	 * Instantiates a new check result.
	 *
	 * @param rule the rule
	 * @param device the device
	 * @param result the result
	 */
	public CheckResult(Rule rule, Device device, CheckResult.ResultOption result) {
		this.key.setRule(rule);
		this.key.setDevice(device);
		this.result = result;
		this.comment = "";
	}

	/**
	 * Instantiates a new check result.
	 *
	 * @param rule the rule
	 * @param device the device
	 * @param result the result
	 * @param comment the comments
	 */
	public CheckResult(Rule rule, Device device, CheckResult.ResultOption result, String comment) {
		this.key.setRule(rule);
		this.key.setDevice(device);
		this.result = result;
		this.comment = comment;
	}

	/**
	 * Sets the comment.
	 *
	 * @param comment the new comment
	 */
	public void setComment(String comment) {
		this.comment = StringUtils.abbreviate(comment, 9900);
	}

	/**
	 * Gets the rule.
	 *
	 * @return the rule
	 */
	@Transient
	public Rule getRule() {
		return this.key.getRule();
	}

	/**
	 * Sets the rule.
	 *
	 * @param rule the new rule
	 */
	public void setRule(Rule rule) {
		this.key.setRule(rule);
	}

	/**
	 * Gets the device.
	 *
	 * @return the device
	 */
	@Transient
	public Device getDevice() {
		return this.key.getDevice();
	}

	/**
	 * Sets the device.
	 *
	 * @param device the new device
	 */
	public void setDevice(Device device) {
		this.key.setDevice(device);
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int hashResult = 1;
		hashResult = prime * hashResult + ((key == null) ? 0 : key.hashCode());
		return hashResult;
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
		CheckResult other = (CheckResult) obj;
		if (key == null) {
			if (other.key != null)
				return false;
		}
		else if (!key.equals(other.key))
			return false;
		return true;
	}

}
