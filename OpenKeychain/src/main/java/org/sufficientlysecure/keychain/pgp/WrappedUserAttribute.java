/*
 * Copyright (C) 2014 Vincent Breitmoser <v.breitmoser@mugenguild.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.pgp;

import org.spongycastle.bcpg.BCPGInputStream;
import org.spongycastle.bcpg.BCPGOutputStream;
import org.spongycastle.bcpg.Packet;
import org.spongycastle.bcpg.UserAttributePacket;
import org.spongycastle.bcpg.UserAttributeSubpacket;
import org.spongycastle.bcpg.UserAttributeSubpacketTags;
import org.spongycastle.openpgp.PGPUserAttributeSubpacketVector;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.io.Serializable;

public class WrappedUserAttribute implements Serializable {

    public static final int UAT_UNKNOWN = 0;
    public static final int UAT_IMAGE = UserAttributeSubpacketTags.IMAGE_ATTRIBUTE;

    private PGPUserAttributeSubpacketVector mVector;

    WrappedUserAttribute(PGPUserAttributeSubpacketVector vector) {
        mVector = vector;
    }

    PGPUserAttributeSubpacketVector getVector() {
        return mVector;
    }

    public int getType() {
        if (mVector.getSubpacket(UserAttributeSubpacketTags.IMAGE_ATTRIBUTE) != null) {
            return UAT_IMAGE;
        }
        return 0;
    }

    public static WrappedUserAttribute fromSubpacket (int type, byte[] data) {
        UserAttributeSubpacket subpacket = new UserAttributeSubpacket(type, data);
        PGPUserAttributeSubpacketVector vector = new PGPUserAttributeSubpacketVector(
                new UserAttributeSubpacket[] { subpacket });

        return new WrappedUserAttribute(vector);

    }

    /** Writes this object to an ObjectOutputStream. */
    private void writeObject(java.io.ObjectOutputStream out) throws IOException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BCPGOutputStream bcpg = new BCPGOutputStream(baos);
        bcpg.writePacket(new UserAttributePacket(mVector.toSubpacketArray()));
        out.writeObject(baos.toByteArray());

    }

    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {

        byte[] data = (byte[]) in.readObject();
        BCPGInputStream bcpg = new BCPGInputStream(new ByteArrayInputStream(data));
        Packet p = bcpg.readPacket();
        if ( ! UserAttributePacket.class.isInstance(p)) {
            throw new IOException("Could not decode UserAttributePacket!");
        }
        mVector = new PGPUserAttributeSubpacketVector(((UserAttributePacket) p).getSubpackets());

    }

    private void readObjectNoData() throws ObjectStreamException {
    }

}
