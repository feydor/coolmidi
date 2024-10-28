package io.feydor.ui.impl.gui;

import javax.swing.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public class DragNDropFileTransferHandler extends TransferHandler {
    private final Consumer<File> fileHandler;

    public DragNDropFileTransferHandler(Consumer<File> fileHandler) {
        this.fileHandler = fileHandler;
    }

    @Override
    public boolean canImport(TransferSupport support) {
        return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) {
            return false;
        }

        Transferable t = support.getTransferable();

        try {
            java.util.List<File> l = (List<File>)t.getTransferData(DataFlavor.javaFileListFlavor);

            for (File f : l) {
                System.out.println("file: " + f.getAbsolutePath());
                fileHandler.accept(f);
            }
        } catch (UnsupportedFlavorException | IOException e) {
            return false;
        }

        return true;
    }
}
