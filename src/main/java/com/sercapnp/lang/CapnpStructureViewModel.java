package com.sercapnp.lang;

import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewModelBase;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public class CapnpStructureViewModel extends StructureViewModelBase
        implements StructureViewModel.ElementInfoProvider {

    public CapnpStructureViewModel(@NotNull PsiFile psiFile) {
        super(psiFile, new CapnpStructureViewElement(psiFile));
    }

    @Override
    public boolean isAlwaysShowsPlus(StructureViewTreeElement element) {
        return false;
    }

    @Override
    public boolean isAlwaysLeaf(StructureViewTreeElement element) {
        return false;
    }
}
