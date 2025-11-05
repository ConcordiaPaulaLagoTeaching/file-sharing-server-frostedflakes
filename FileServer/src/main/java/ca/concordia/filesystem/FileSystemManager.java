package ca.concordia.filesystem;

import ca.concordia.filesystem.datastructures.FEntry;

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
    
    private FEntry[] inodeTable; // Array of inodes
    private boolean[] freeBlockList; // Bitmap for free blocks

    public FileSystemManager(String filename, int totalSize) {

        if (instance == null) {
            try {

                // Open or create the disk file and set its size
                this.disk = new RandomAccessFile(filename, "rw");
                this.disk.setLength(totalSize);

                // Initialize inode table and free block list
                inodeTable = new FEntry[MAXFILES];
                freeBlockList = new boolean[MAXBLOCKS];

                // Mark all FEntries as unused
                for (int i = 0; i < MAXFILES; i++)
                    inodeTable[i] = new FEntry("", (short) 0, (short) -1);

                // Mark all blocks as free
                for (int i = 0; i < MAXBLOCKS; i++)
                    freeBlockList[i] = true;

                System.out.println("File system initialized.");

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

                disk.seek(firstBlock * BLOCK_SIZE);
                byte[] zeros = new byte[BLOCK_SIZE];
                disk.write(zeros);
            }

            //clear the entry
            inodeTable[index] = new FEntry("", (short) 0, (short) -1);
            System.out.println("File" + fileName + "deleted successfully.");

        } finally {
            writeLock.unlock();
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
