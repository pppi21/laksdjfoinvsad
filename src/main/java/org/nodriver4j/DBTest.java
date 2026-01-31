package org.nodriver4j;

import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.entity.ProfileGroupEntity;
import org.nodriver4j.persistence.importer.ProfileImporter;
import org.nodriver4j.persistence.repository.ProfileGroupRepository;
import org.nodriver4j.persistence.repository.ProfileRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class DBTest {
    public static void main(String[] args) throws IOException {
        Database.initialize();
        ProfileRepository profileRepo = new ProfileRepository();
        ProfileGroupRepository profileGroupRepo = new ProfileGroupRepository();

        List<ProfileEntity> groupProfiles = profileRepo.findByGroupId(1);

        System.out.println(groupProfiles);

        profileGroupRepo.deleteAll();

        System.out.println(groupProfiles);

    }
}
