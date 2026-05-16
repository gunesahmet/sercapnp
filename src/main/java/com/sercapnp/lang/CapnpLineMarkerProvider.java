package com.sercapnp.lang;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.PlatformIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Collection;
import java.util.List;

/**
 * Shows gutter icons for struct, enum, interface, and const definitions
 * in .capnp files.
 *
 * Since we have a flat lexer (no PSI tree), this provider checks each
 * KEYWORD token to see if it's a definition keyword at the right position.
 */
public class CapnpLineMarkerProvider implements LineMarkerProvider {

    @Nullable
    @Override
    public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        // Only process elements in capnp files
        PsiFile file = element.getContainingFile();
        if (!(file instanceof CapnpFile)) return null;

        // Check if this element is a keyword token
        if (element.getNode().getElementType() != CapnpTypes.KEYWORD) return null;

        String text = element.getText();
        if (!"struct".equals(text) && !"enum".equals(text) &&
                !"interface".equals(text) && !"const".equals(text) &&
                !"annotation".equals(text)) {
            return null;
        }

        // Verify this is a definition (not a reference inside annotation targets etc.)
        // Check: the keyword should be at or near the start of a line
        String fileText = file.getText();
        int offset = element.getTextOffset();

        // Find line start
        int lineStart = fileText.lastIndexOf('\n', offset - 1) + 1;
        String beforeKeyword = fileText.substring(lineStart, offset).trim();

        // For top-level definitions, nothing before the keyword (or just whitespace)
        // For nested definitions inside blocks, also just whitespace
        // But NOT after ':' (that's a type reference) or inside annotation target list
        if (!beforeKeyword.isEmpty()) return null;

        // Get the icon based on keyword
        Icon icon;
        String tooltip;
        switch (text) {
            case "struct":
                icon = PlatformIcons.CLASS_ICON;
                tooltip = "Struct definition";
                break;
            case "enum":
                icon = PlatformIcons.ENUM_ICON;
                tooltip = "Enum definition";
                break;
            case "interface":
                icon = PlatformIcons.INTERFACE_ICON;
                tooltip = "Interface definition";
                break;
            case "const":
                icon = PlatformIcons.VARIABLE_ICON;
                tooltip = "Const definition";
                break;
            case "annotation":
                icon = PlatformIcons.ANNOTATION_TYPE_ICON;
                tooltip = "Annotation definition";
                break;
            default:
                return null;
        }

        return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                icon,
                e -> tooltip,
                null,
                GutterIconRenderer.Alignment.LEFT,
                () -> tooltip
        );
    }
}
