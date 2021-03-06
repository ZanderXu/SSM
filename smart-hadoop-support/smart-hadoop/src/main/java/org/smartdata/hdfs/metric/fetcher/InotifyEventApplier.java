/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.smartdata.hdfs.metric.fetcher;

import org.apache.hadoop.fs.XAttr;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.inotify.Event;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartdata.hdfs.HadoopUtil;
import org.smartdata.metastore.DBType;
import org.smartdata.metastore.MetaStore;
import org.smartdata.metastore.MetaStoreException;
import org.smartdata.model.BackUpInfo;
import org.smartdata.model.FileDiff;
import org.smartdata.model.FileDiffType;
import org.smartdata.model.FileInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * This is a very preliminary and buggy applier, can further enhance by referring to
 * {@link org.apache.hadoop.hdfs.server.namenode.FSEditLogLoader}
 */
public class InotifyEventApplier {
  private final MetaStore metaStore;
  private DFSClient client;
  private static final Logger LOG =
      LoggerFactory.getLogger(InotifyEventFetcher.class);

  public InotifyEventApplier(MetaStore metaStore, DFSClient client) {
    this.metaStore = metaStore;
    this.client = client;
  }


  public void apply(List<Event> events) throws IOException, MetaStoreException {
    List<String> statements = new ArrayList<>();
    for (Event event : events) {
      List<String> gen = getSqlStatement(event);
      if (gen != null && !gen.isEmpty()){
        for (String s : gen) {
          if (s != null && s.length() > 0) {
            statements.add(s);
          }
        }
      }
    }
    this.metaStore.execute(statements);
  }

  //check if the dir is in ignoreList

  public void apply(Event[] events) throws IOException, MetaStoreException {
    this.apply(Arrays.asList(events));
  }

  private List<String> getSqlStatement(Event event) throws IOException, MetaStoreException {
    LOG.debug("Even Type = {}", event.getEventType().toString());
    switch (event.getEventType()) {
      case CREATE:
        LOG.trace("event type:" + event.getEventType().name() +
            ", path:" + ((Event.CreateEvent) event).getPath());
        return Arrays.asList(this.getCreateSql((Event.CreateEvent) event));
      case CLOSE:
        LOG.trace("event type:" + event.getEventType().name() +
            ", path:" + ((Event.CloseEvent) event).getPath());
        return Arrays.asList(this.getCloseSql((Event.CloseEvent) event));
      case RENAME:
        LOG.trace("event type:" + event.getEventType().name() +
            ", src path:" + ((Event.RenameEvent) event).getSrcPath() +
            ", dest path:" + ((Event.RenameEvent) event).getDstPath());
        return this.getRenameSql((Event.RenameEvent)event);
      case METADATA:
        LOG.trace("event type:" + event.getEventType().name() +
            ", path:" + ((Event.MetadataUpdateEvent)event).getPath());
        return Arrays.asList(this.getMetaDataUpdateSql((Event.MetadataUpdateEvent)event));
      case APPEND:
        LOG.trace("event type:" + event.getEventType().name() +
            ", path:" + ((Event.AppendEvent)event).getPath());
        return this.getAppendSql((Event.AppendEvent)event);
      case UNLINK:
        LOG.trace("event type:" + event.getEventType().name() +
            ", path:" + ((Event.UnlinkEvent)event).getPath());
        return this.getUnlinkSql((Event.UnlinkEvent)event);
    }
    return Arrays.asList();
  }

  //Todo: times and ec policy id, etc.
  private String getCreateSql(Event.CreateEvent createEvent) throws IOException, MetaStoreException {
    HdfsFileStatus fileStatus = client.getFileInfo(createEvent.getPath());
    if (fileStatus == null) {
      LOG.debug("Can not get HdfsFileStatus for file " + createEvent.getPath());
      return "";
    }
    FileInfo fileInfo = HadoopUtil.convertFileStatus(fileStatus, createEvent.getPath());

    if (inBackup(fileInfo.getPath())) {
      if (!fileInfo.isdir()) {

        // ignore dir
        FileDiff fileDiff = new FileDiff(FileDiffType.APPEND);
        fileDiff.setSrc(fileInfo.getPath());
        fileDiff.getParameters().put("-offset", String.valueOf(0));
        // Note that "-length 0" means create an empty file
        fileDiff.getParameters()
            .put("-length", String.valueOf(fileInfo.getLength()));
        // TODO add support in CopyFileAction or split into two file diffs
        //add modification_time and access_time to filediff
        fileDiff.getParameters().put("-mtime", "" + fileInfo.getModificationTime());
        // fileDiff.getParameters().put("-atime", "" + fileInfo.getAccessTime());
        //add owner to filediff
        fileDiff.getParameters().put("-owner", "" + fileInfo.getOwner());
        fileDiff.getParameters().put("-group", "" + fileInfo.getGroup());
        //add Permission to filediff
        fileDiff.getParameters().put("-permission", "" + fileInfo.getPermission());
        //add replication count to file diff
        fileDiff.getParameters().put("-replication", "" + fileInfo.getBlockReplication());
        metaStore.insertFileDiff(fileDiff);
      }
    }
    metaStore.deleteFileByPath(fileInfo.getPath());
    metaStore.deleteFileState(fileInfo.getPath());
    metaStore.insertFile(fileInfo);
    return "";
  }

  private boolean inBackup(String src) throws MetaStoreException {
    if (metaStore.srcInbackup(src)) {
      return true;
    }
    return false;
  }

  //Todo: should update mtime? atime?
  private String getCloseSql(Event.CloseEvent closeEvent) throws IOException, MetaStoreException {
    FileDiff fileDiff = new FileDiff(FileDiffType.APPEND);
    fileDiff.setSrc(closeEvent.getPath());
    long newLen = closeEvent.getFileSize();
    long currLen = 0l;
    // TODO make sure offset is correct
    if (inBackup(closeEvent.getPath())) {
      FileInfo fileInfo = metaStore.getFile(closeEvent.getPath());
      if (fileInfo == null) {
        // TODO add metadata
        currLen = 0;
      } else {
        currLen = fileInfo.getLength();
      }
      if (currLen != newLen) {
        fileDiff.getParameters().put("-offset", String.valueOf(currLen));
        fileDiff.getParameters()
            .put("-length", String.valueOf(newLen - currLen));
        metaStore.insertFileDiff(fileDiff);
      }
    }
    return String.format(
        "UPDATE file SET length = %s, modification_time = %s WHERE path = '%s';",
        closeEvent.getFileSize(), closeEvent.getTimestamp(), closeEvent.getPath());
  }

  //Todo: should update mtime? atime?
//  private String getTruncateSql(Event.TruncateEvent truncateEvent) {
//    return String.format(
//        "UPDATE file SET length = %s, modification_time = %s WHERE path = '%s';",
//        truncateEvent.getFileSize(), truncateEvent.getTimestamp(), truncateEvent.getPath());
//  }

  private List<String> getRenameSql(Event.RenameEvent renameEvent)
      throws IOException, MetaStoreException {
    String src = renameEvent.getSrcPath();
    String dest = renameEvent.getDstPath();
    List<String> ret = new ArrayList<>();
    HdfsFileStatus status = client.getFileInfo(dest);
    FileInfo info = metaStore.getFile(src);
    if (inBackup(src)) {
      // rename the file if the renamed file is still under the backup src dir
      // if not, insert a delete file diff
      if (inBackup(dest)) {
        FileDiff fileDiff = new FileDiff(FileDiffType.RENAME);
        fileDiff.setSrc(src);
        fileDiff.getParameters().put("-dest", dest);
        metaStore.insertFileDiff(fileDiff);
      } else {
        insertDeleteDiff(src, info.isdir());
      }
    } else if (inBackup(dest)) {
      // tackle such case: rename file from outside into backup dir
      if (!info.isdir()) {
        FileDiff fileDiff = new FileDiff(FileDiffType.APPEND);
        fileDiff.setSrc(dest);
        fileDiff.getParameters().put("-offset", String.valueOf(0));
        fileDiff.getParameters()
            .put("-length", String.valueOf(info.getLength()));
        metaStore.insertFileDiff(fileDiff);
      } else {
        List<FileInfo> fileInfos = metaStore.getFilesByPrefix(src.endsWith("/") ? src : src + "/");
        for (FileInfo fileInfo : fileInfos) {
          // TODO: cover subdir with no file case
          if (fileInfo.isdir()) {
            continue;
          }
          FileDiff fileDiff = new FileDiff(FileDiffType.APPEND);
          fileDiff.setSrc(fileInfo.getPath().replaceFirst(src, dest));
          fileDiff.getParameters().put("-offset", String.valueOf(0));
          fileDiff.getParameters()
              .put("-length", String.valueOf(fileInfo.getLength()));
          metaStore.insertFileDiff(fileDiff);
        }
      }
    }
    if (status == null) {
      LOG.debug("Get rename dest status failed, {} -> {}", src, dest);
    }
    if (info == null) {
      if (status != null) {
        info = HadoopUtil.convertFileStatus(status, dest);
        metaStore.insertFile(info);
      }
    } else {
      ret.add(String.format("UPDATE file SET path = replace(path, '%s', '%s') "
          + "WHERE path = '%s';", src, dest, src));
      ret.add(String.format("UPDATE file_state SET path = replace(path, '%s', '%s') "
          + "WHERE path = '%s';", src, dest, src));
      ret.add(String.format("UPDATE small_file SET path = replace(path, '%s', '%s') "
          + "WHERE path = '%s';", src, dest, src));
      if (info.isdir()) {
        if (metaStore.getDbType() == DBType.MYSQL) {
          ret.add(String.format("UPDATE file SET path = CONCAT('%s', SUBSTR(path, %d)) "
              + "WHERE path LIKE '%s/%%';", dest, src.length() + 1, src));
          ret.add(String.format("UPDATE file_state SET path = CONCAT('%s', SUBSTR(path, %d)) "
              + "WHERE path LIKE '%s/%%';", dest, src.length() + 1, src));
          ret.add(String.format("UPDATE small_file SET path = CONCAT('%s', SUBSTR(path, %d)) "
              + "WHERE path LIKE '%s/%%';", dest, src.length() + 1, src));
        } else if (metaStore.getDbType() == DBType.SQLITE) {
          ret.add(String.format("UPDATE file SET path = '%s' || SUBSTR(path, %d) "
              + "WHERE path LIKE '%s/%%';", dest, src.length() + 1, src));
          ret.add(String.format("UPDATE file_state SET path = '%s' || SUBSTR(path, %d) "
              + "WHERE path LIKE '%s/%%';", dest, src.length() + 1, src));
          ret.add(String.format("UPDATE small_file SET path = '%s' || SUBSTR(path, %d) "
              + "WHERE path LIKE '%s/%%';", dest, src.length() + 1, src));
        }
      }
    }
    return ret;
  }

  private String getMetaDataUpdateSql(Event.MetadataUpdateEvent metadataUpdateEvent) throws MetaStoreException {

    FileDiff fileDiff = null;
    if (inBackup(metadataUpdateEvent.getPath())) {
      fileDiff = new FileDiff(FileDiffType.METADATA);
      fileDiff.setSrc(metadataUpdateEvent.getPath());
    }
    switch (metadataUpdateEvent.getMetadataType()) {
      case TIMES:
        if (metadataUpdateEvent.getMtime() > 0 && metadataUpdateEvent.getAtime() > 0) {
          if (fileDiff != null) {
            fileDiff.getParameters().put("-mtime", "" + metadataUpdateEvent.getMtime());
            // fileDiff.getParameters().put("-access_time", "" + metadataUpdateEvent.getAtime());
            metaStore.insertFileDiff(fileDiff);
          }
          return String.format(
            "UPDATE file SET modification_time = %s, access_time = %s WHERE path = '%s';",
            metadataUpdateEvent.getMtime(),
            metadataUpdateEvent.getAtime(),
            metadataUpdateEvent.getPath());
        } else if (metadataUpdateEvent.getMtime() > 0) {
          if (fileDiff != null) {
            fileDiff.getParameters().put("-mtime", "" + metadataUpdateEvent.getMtime());
            metaStore.insertFileDiff(fileDiff);
          }
          return String.format(
            "UPDATE file SET modification_time = %s WHERE path = '%s';",
            metadataUpdateEvent.getMtime(),
            metadataUpdateEvent.getPath());
        } else if (metadataUpdateEvent.getAtime() > 0) {
          // if (fileDiff != null) {
          //   fileDiff.getParameters().put("-access_time", "" + metadataUpdateEvent.getAtime());
          //   metaStore.insertFileDiff(fileDiff);
          // }
          return String.format(
            "UPDATE file SET access_time = %s WHERE path = '%s';",
            metadataUpdateEvent.getAtime(),
            metadataUpdateEvent.getPath());
        } else {
          return "";
        }
      case OWNER:
        if (fileDiff != null) {
          fileDiff.getParameters().put("-owner", "" + metadataUpdateEvent.getOwnerName());
          metaStore.insertFileDiff(fileDiff);
        }
        return String.format(
            "UPDATE file SET owner = '%s', owner_group = '%s' WHERE path = '%s';",
            metadataUpdateEvent.getOwnerName(),
            metadataUpdateEvent.getGroupName(),
            metadataUpdateEvent.getPath());
      case PERMS:
        if (fileDiff != null) {
          fileDiff.getParameters().put("-permission", "" + metadataUpdateEvent.getPerms().toShort());
          metaStore.insertFileDiff(fileDiff);
        }
        return String.format(
            "UPDATE file SET permission = %s WHERE path = '%s';",
            metadataUpdateEvent.getPerms().toShort(), metadataUpdateEvent.getPath());
      case REPLICATION:
        if (fileDiff != null) {
          fileDiff.getParameters().put("-replication", "" + metadataUpdateEvent.getReplication());
          metaStore.insertFileDiff(fileDiff);
        }
        return String.format(
            "UPDATE file SET block_replication = %s WHERE path = '%s';",
            metadataUpdateEvent.getReplication(), metadataUpdateEvent.getPath());
      case XATTRS:
        //Todo
        if (LOG.isDebugEnabled()) {
          String message = "\n";
          for (XAttr xAttr : metadataUpdateEvent.getxAttrs()) {
            message += xAttr.toString() + "\n";
          }
          LOG.debug(message);
        }
        break;
      case ACLS:
        return "";
    }
    return "";
  }

  private List<String> getAppendSql(Event.AppendEvent appendEvent) {
    //Do nothing;
    return Arrays.asList();
  }

  private List<String> getUnlinkSql(Event.UnlinkEvent unlinkEvent) throws MetaStoreException {
    // delete root, i.e., /
    String root = "/";
    if (root.equals(unlinkEvent.getPath())) {
      LOG.warn("Deleting root directory!!!");
      insertDeleteDiff(root, true);
      return Arrays.asList(
          String.format("DELETE FROM file WHERE path like '%s%%'", root),
          String.format("DELETE FROM file_state WHERE path like '%s%%'", root),
          String.format("DELETE FROM small_file WHERE path like '%s%%'", root));
    }
    String path = unlinkEvent.getPath();
    // file has no "/" appended in the metaStore
    FileInfo fileInfo = metaStore.getFile(path.endsWith("/") ?
        path.substring(0, path.length() - 1) : path);
    if (fileInfo == null) return Arrays.asList();
    if (fileInfo.isdir()) {
      insertDeleteDiff(unlinkEvent.getPath(), true);
      // delete all files in this dir from file table
      return Arrays.asList(
          String.format("DELETE FROM file WHERE path LIKE '%s/%%';", unlinkEvent.getPath()),
          String.format("DELETE FROM file WHERE path = '%s';", unlinkEvent.getPath()),
          String.format("DELETE FROM file_state WHERE path LIKE '%s/%%';", unlinkEvent.getPath()),
          String.format("DELETE FROM file_state WHERE path = '%s';", unlinkEvent.getPath()),
          String.format("DELETE FROM small_file WHERE path LIKE '%s/%%';", unlinkEvent.getPath()),
          String.format("DELETE FROM small_file WHERE path = '%s';", unlinkEvent.getPath()));
    } else {
      insertDeleteDiff(unlinkEvent.getPath(), false);
      // delete file in file table
      return Arrays.asList(
          String.format("DELETE FROM file WHERE path = '%s';", unlinkEvent.getPath()),
          String.format("DELETE FROM file_state WHERE path = '%s';", unlinkEvent.getPath()),
          String.format("DELETE FROM small_file WHERE path = '%s';", unlinkEvent.getPath()));
    }
  }

  // TODO: just insert a fileDiff for this kind of path.
  // It seems that there is no need to see if path matches with one dir in FileInfo.
  private void insertDeleteDiff(String path, boolean isDir) throws MetaStoreException {
    if (isDir) {
      path = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
      List<FileInfo> fileInfos = metaStore.getFilesByPrefix(path);
      for (FileInfo fileInfo : fileInfos) {
        if (fileInfo.isdir()) {
          if (path.equals(fileInfo.getPath())) {
            insertDeleteDiff(fileInfo.getPath());
            break;
          }
        }
      }
    } else {
      insertDeleteDiff(path);
    }
  }

  private void insertDeleteDiff(String path) throws MetaStoreException {
    // TODO: remove "/" appended in src or dest in backup_file table
    String pathWithSlash = path.endsWith("/") ? path : path + "/";
    if (inBackup(pathWithSlash)) {
      List<BackUpInfo> backUpInfos = metaStore.getBackUpInfoBySrc(pathWithSlash);
      for (BackUpInfo backUpInfo : backUpInfos) {
        String destPath = pathWithSlash.replaceFirst(backUpInfo.getSrc(), backUpInfo.getDest());
        try {
          // tackle root path case
          URI namenodeUri = new URI(destPath);
          String root = "hdfs://" + namenodeUri.getHost() + ":"
              + String.valueOf(namenodeUri.getPort());
          if (destPath.equals(root) || destPath.equals(root + "/") || destPath.equals("/")) {
            for (String srcFilePath : getFilesUnderDir(pathWithSlash)) {
              FileDiff fileDiff = new FileDiff(FileDiffType.DELETE);
              fileDiff.setSrc(srcFilePath);
              String destFilePath = srcFilePath.replaceFirst(backUpInfo.getSrc(), backUpInfo.getDest());
              fileDiff.getParameters().put("-dest", destFilePath);
              metaStore.insertFileDiff(fileDiff);
            }
          } else {
            FileDiff fileDiff = new FileDiff(FileDiffType.DELETE);
            // use the path getting from event with no slash appended
            fileDiff.setSrc(path);
            // put sync's dest path in parameter for delete use
            fileDiff.getParameters().put("-dest", destPath);
            metaStore.insertFileDiff(fileDiff);
          }
        } catch (URISyntaxException e) {
          LOG.error("Error occurs!", e);
        }
      }
    }
  }

  private List<String> getFilesUnderDir(String dir) throws MetaStoreException {
    dir = dir.endsWith("/") ? dir : dir + "/";
    List<String> fileList = new ArrayList<>();
    List<String> subdirList = new ArrayList<>();
    // get fileInfo in asc order of path to guarantee that
    // the subdir is tackled prior to files or dirs under it
    List<FileInfo> fileInfos = metaStore.getFilesByPrefixInOrder(dir);
    for (FileInfo fileInfo : fileInfos) {
      // just delete subdir instead of deleting all files under it
      if (isUnderDir(fileInfo.getPath(), subdirList)) {
        continue;
      }
      fileList.add(fileInfo.getPath());
      if (fileInfo.isdir()) {
        subdirList.add(fileInfo.getPath());
      }
    }
    return fileList;
  }

  private boolean isUnderDir(String path, List<String> dirs) {
    if (dirs.isEmpty()) {
      return false;
    }
    for (String subdir : dirs) {
      if (path.startsWith(subdir)) {
        return true;
      }
    }
    return false;
  }
}
