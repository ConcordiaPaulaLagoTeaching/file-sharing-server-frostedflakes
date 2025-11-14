package ca.concordia.filesystem;

import ca.concordia.filesystem.datastructures.FEntry;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class FileSystemManager {

    private final int MAXFILES = 5;
    private final int MAXBLOCKS = 10;
    private static FileSystemManager instance;
    private final RandomAccessFile disk;

    //read-write lock to allow multiple readers or a single writer at a time for thread-safe file system access
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = rwLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();

    private static final int BLOCK_SIZE = 128; // Example block size
    // Reserve one block at the start of the disk for metadata so data blocks don't overlap metadata
    private static final int BLOCK_DATA_OFFSET = BLOCK_SIZE;

    private FEntry[] inodeTable; // Array of inodes
    private boolean[] freeBlockList; // Bitmap for free blocks
    private int[] blockNext;

    // saves the filesystem’s metadata so it’s still there next time the program runs
    private void saveMetadata() throws Exception {
        disk.seek(0);

        for (int i = 0; i < inodeTable.length; i++) {
            FEntry e = inodeTable[i];
            disk.writeUTF(e.getFilename());
            disk.writeShort(e.getFilesize());
            disk.writeShort(e.getFirstBlock());
        }

        for (int i = 0; i < freeBlockList.length; i++) {
            disk.writeBoolean(freeBlockList[i]);
        }

        for (int i = 0; i < blockNext.length; i++) {
            disk.writeInt(blockNext[i]);
        }
    }

    public FileSystemManager(String filename, int totalSize) {

        if (instance == null) {
            try {

                // Open or create the disk file and set its size
                this.disk = new RandomAccessFile(filename, "rw");

                // Initialize inode table and free block list
                inodeTable = new FEntry[MAXFILES];
                freeBlockList = new boolean[MAXBLOCKS];
                blockNext = new int[MAXBLOCKS];  // create the array that keeps track of how blocks are linked together

                File f = new File(filename);
                boolean fresh = !f.exists() || this.disk.length() == 0;  // check if disk file is new or empty

                if (fresh) {  // creating a new empty filesystem for the first run
                    // allocate extra space for metadata block so data blocks start after metadata
                    this.disk.setLength(totalSize + BLOCK_DATA_OFFSET);

                    // Mark all FEntries as unused
                    for (int i = 0; i < MAXFILES; i++)
                        inodeTable[i] = new FEntry("", (short) 0, (short) -1);

                    // Mark all blocks as free
                    for (int i = 0; i < MAXBLOCKS; i++) {
                        freeBlockList[i] = true;
                        blockNext[i] = -1;
                    }

                    saveMetadata();
                    System.out.println("File system initialized.");

                } else { // reload the filesystem metadata that was stored on disk
                    this.disk.seek(0);

                    for (int i = 0; i < MAXFILES; i++) {
                        String name = disk.readUTF();
                        short size = disk.readShort();
                        short first = disk.readShort();
                        inodeTable[i] = new FEntry(name, size, first);
                    }

                    for (int i = 0; i < MAXBLOCKS; i++)
                        freeBlockList[i] = disk.readBoolean();

                    for (int i = 0; i < MAXBLOCKS; i++)
                        blockNext[i] = disk.readInt();

                    System.out.println("Existing filesystem loaded.");
                }

                // Set the singleton instance
                instance = this;

            } catch (Exception e) {
                throw new RuntimeException("Error initializing the FileSystemManager: ", e);
            }

        }
        else {
            throw new IllegalStateException("FileSystemManager is already initialized.");
        }
    }


    public void createFile(String fileName) throws Exception {

        if (fileName.length() > 11) {
            throw new IllegalArgumentException("Filename cannot be longer than 11 characters.");
        }

        //lock for writing
        writeLock.lock();
        try {

            //check if file already exists
            for (FEntry entry : inodeTable) {
                if (entry.getFilename().equals(fileName)) {
                    throw new IllegalArgumentException("Filename already exists.");
                }
            }

            //find an empty slot (free FEntry) in the inode table
            int freeIndex = -1;
            for (int i = 0; i < inodeTable.length; i++) {
                if (inodeTable[i].getFirstBlock() == -1 && inodeTable[i].getFilename().isEmpty()) {
                    freeIndex = i;
                    break;
                }
            }

            if (freeIndex == -1) {
                throw new IllegalArgumentException("No space for new file.");
            }

            //create a new file entry and assign it to the free index
            inodeTable[freeIndex] = new FEntry(fileName, (short) 0, (short) -1);

            saveMetadata();

            System.out.println("File created successfully.");

        } finally {

            //release the lock
            writeLock.unlock();
        }

    }


    public void deleteFile(String fileName) throws Exception {

        if (fileName.length() > 11) {
            throw new IllegalArgumentException("Filename cannot be longer than 11 characters.");
        }

        writeLock.lock();
        try {

            //find the file in the table
            int index = -1;
            for (int i = 0; i < inodeTable.length; i++) {
                if (inodeTable[i].getFilename().equals(fileName)) {
                    index = i;
                    break;
                }
            }

            if (index == -1) {
                throw new IllegalArgumentException("File " + fileName + " does not exist.");
            }

            //free the file’s block
            short firstBlock = inodeTable[index].getFirstBlock();
                if (firstBlock != -1) {

                    freeBlockList[firstBlock] = true;

                    disk.seek(((long) firstBlock * BLOCK_SIZE) + BLOCK_DATA_OFFSET);
                    byte[] zeros = new byte[BLOCK_SIZE];
                    disk.write(zeros);
                }

            //clear the entry
            inodeTable[index] = new FEntry("", (short) 0, (short) -1);
            saveMetadata();
            System.out.println("File" + fileName + "deleted successfully.");

        } finally {
            writeLock.unlock();
        }
    }

    public void writeFile(String fileName, byte[] contents) throws Exception {

        if (fileName.length() > 11) {
            throw new IllegalArgumentException("filename too long");
        }

        writeLock.lock();
        try {
            //find file and throw error if not
            int index = -1;
            for (int i = 0; i < inodeTable.length; i++) {
                if (inodeTable[i].getFilename().equals(fileName)) {
                    index = i;
                    break;
                }
            }

            if (index == -1) {
                throw new IllegalArgumentException("File " + fileName + " does not exist");
            }

            int fileSize = (contents == null) ? 0 : contents.length;
            int requiredBlocks;
            if(fileSize == 0){
                requiredBlocks = 0;
            }else{
                requiredBlocks = (fileSize + BLOCK_SIZE - 1) / BLOCK_SIZE;
            }

            //copy current blocks of the file to currentBlocks
            short firstBlock = inodeTable[index].getFirstBlock();
            java.util.List<Integer> currentBlocks = new java.util.ArrayList<>();
            if (firstBlock != -1) {
                int cur = firstBlock;
                while (cur != -1) {
                    if (cur < 0 || cur >= MAXBLOCKS) throw new IllegalStateException("error bad index " + cur);
                    currentBlocks.add(cur);
                    cur = blockNext[cur];
                }
            }
            int currentCount = currentBlocks.size();
            //count free blocks
            int freeCount = 0;
            for (int i = 0; i < MAXBLOCKS; i++) {
                boolean inCurrent = currentBlocks.contains(i);
                if (!inCurrent && freeBlockList[i]) freeCount++;
            }

            if (requiredBlocks > (freeCount + currentCount)) {
                throw new IllegalArgumentException("not enough blocks for write");
            }

            //copy current blocks then put new ones
            int[] targetBlocks = new int[requiredBlocks];
            int filled = 0;
            for (int b : currentBlocks) {
                if (filled >= requiredBlocks) break;
                targetBlocks[filled++] = b;
            }
            if (filled < requiredBlocks) {
                for (int i = 0; i < MAXBLOCKS && filled < requiredBlocks; i++) {
                    if (freeBlockList[i] && !currentBlocks.contains(i)) {
                        targetBlocks[filled++] = i;
                    }
                }
            }

            //write content to the target blocks
            byte[] blockBuffer = new byte[BLOCK_SIZE];
            int bytesWrittenBlocks = 0;
            try {
                for (int i = 0; i < requiredBlocks; i++) {
                    int blockIndex = targetBlocks[i];
                    int offset = i * BLOCK_SIZE;
                    int len = Math.min(BLOCK_SIZE, fileSize - offset);
                    //do buffer
                    java.util.Arrays.fill(blockBuffer, (byte) 0);
                    if (len > 0) System.arraycopy(contents, offset, blockBuffer, 0, len);
                    disk.seek(((long) blockIndex * BLOCK_SIZE) + BLOCK_DATA_OFFSET);
                    disk.write(blockBuffer);
                    bytesWrittenBlocks++;
                }
            } catch (Exception e) {
                // attempt to zero any partially written target blocks
                try {
                    byte[] zeros = new byte[BLOCK_SIZE];
                    for (int j = 0; j < bytesWrittenBlocks; j++) {
                        int b = targetBlocks[j];
                        disk.seek(((long) b * BLOCK_SIZE) + BLOCK_DATA_OFFSET);
                        disk.write(zeros);
                    }
                } catch (Exception ignored) {
                }
                throw new RuntimeException("error writting ", e);
            }

            //update new blocks as used
            for (int b : targetBlocks) {
                freeBlockList[b] = false;
            }

            //make old blocks as free
            for (int oldB : currentBlocks) {
                boolean stillUsed = false;
                for (int b : targetBlocks) if (b == oldB) { stillUsed = true; break; }
                if (!stillUsed) {
                    freeBlockList[oldB] = true;
                    disk.seek(((long) oldB * BLOCK_SIZE) + BLOCK_DATA_OFFSET);
                    byte[] zeros = new byte[BLOCK_SIZE];
                    disk.write(zeros);
                    blockNext[oldB] = -1;
                }
            }

            //set up blockNext
            for (int i = 0; i < requiredBlocks; i++) {
                int b = targetBlocks[i];
                int next = (i + 1 < requiredBlocks) ? targetBlocks[i + 1] : -1;
                blockNext[b] = next;
            }

            //update inode
            short first = (requiredBlocks == 0) ? (short) -1 : (short) targetBlocks[0];
            inodeTable[index] = new FEntry(fileName, (short) fileSize, first);

            saveMetadata();
        } finally {
            writeLock.unlock();
        }
    }

    public byte[] readFile(String fileName) throws Exception {
        if (fileName.length() > 11) {
            throw new IllegalArgumentException("filename too long");
        }

        readLock.lock();
        try {
            int index = -1;
            //look for file
            for (int i = 0; i < inodeTable.length; i++) {
                if (inodeTable[i].getFilename().equals(fileName)) {
                    index = i;
                    break;
                }
            }

            if (index == -1) throw new IllegalArgumentException("file " + fileName + " does not exist");

            short firstBlock = inodeTable[index].getFirstBlock();
            int fileSize = inodeTable[index].getFilesize();
            if (firstBlock == -1 || fileSize == 0) return new byte[0];

            byte[] result = new byte[fileSize];
            int written = 0;
            int current = firstBlock;
            byte[] blockBuf = new byte[BLOCK_SIZE];

            while (current != -1 && written < fileSize) {
                if (current < 0 || current >= MAXBLOCKS) throw new IllegalStateException("bad index: " + current);
                disk.seek(((long) current * BLOCK_SIZE) + BLOCK_DATA_OFFSET);
                int toRead = Math.min(BLOCK_SIZE, fileSize - written);
                //read block and copy
                disk.readFully(blockBuf);
                System.arraycopy(blockBuf, 0, result, written, toRead);
                written += toRead;
                current = blockNext[current];
            }

            return result;

        } finally {
            readLock.unlock();
        }
    }


    public String[] listFiles() {

        readLock.lock();
        try {

            //count non-empty entries
            int count = 0;
            for (int i = 0; i < inodeTable.length; i++) {
                if (!inodeTable[i].getFilename().isEmpty()) {
                    count++;
                }
            }

            //store file names
            String[] files = new String[count];
            int idx = 0;
            for (int i = 0; i < inodeTable.length; i++) {
                if (!inodeTable[i].getFilename().isEmpty()) {
                    files[idx++] = inodeTable[i].getFilename();
                }
            }
            return files;

        } finally {
            readLock.unlock();
        }
    }

}
