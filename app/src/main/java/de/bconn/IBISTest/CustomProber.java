package de.bconn.IBISTest;

import de.bconn.IBISTest.driver.CdcAcmSerialDriver;
import de.bconn.IBISTest.driver.ProbeTable;
import de.bconn.IBISTest.driver.UsbSerialProber;

/**
 * add devices here, that are not known to DefaultProber
 *
 * if the App should auto start for these devices, also
 * add IDs to app/src/main/res/xml/device_filter.xml
 */
class CustomProber {

    static UsbSerialProber getCustomProber() {
        ProbeTable customTable = new ProbeTable();
        customTable.addProduct(0x0403, 0x6001, CdcAcmSerialDriver.class); // e.g. Digispark CDC
        return new UsbSerialProber(customTable);
    }

}
