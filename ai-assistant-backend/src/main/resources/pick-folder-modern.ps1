# Modern Explorer-style folder picker (IFileOpenDialog / Vista+)
param(
    [ValidateSet('open', 'save')]
    [string]$Mode = 'open'
)

$ErrorActionPreference = 'Stop'

if (-not ('PassAi.ModernFolderPicker' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

namespace PassAi {
    public static class ModernFolderPicker {
        [ComImport]
        [Guid("DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7")]
        private class FileOpenDialogRCW { }

        [ComImport]
        [Guid("42f85136-db7e-439c-85f1-e4075d135fc8")]
        [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IFileDialog {
            [PreserveSig] int Show([In] IntPtr parent);
            void SetFileTypes([In] uint cFileTypes, [In] IntPtr rgFilterSpec);
            void SetFileTypeIndex([In] uint iFileType);
            void GetFileTypeIndex(out uint piFileType);
            void Advise([In] IntPtr pfde, out uint pdwCookie);
            void Unadvise([In] uint dwCookie);
            void SetOptions([In] uint fos);
            void GetOptions(out uint pfos);
            void SetDefaultFolder([In] IShellItem psi);
            void SetFolder([In] IShellItem psi);
            void GetFolder(out IShellItem ppsi);
            void GetCurrentSelection(out IShellItem ppsi);
            void SetFileName([In, MarshalAs(UnmanagedType.LPWStr)] string pszName);
            void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string pszName);
            void SetTitle([In, MarshalAs(UnmanagedType.LPWStr)] string pszTitle);
            void SetOkButtonLabel([In, MarshalAs(UnmanagedType.LPWStr)] string pszText);
            void SetFileNameLabel([In, MarshalAs(UnmanagedType.LPWStr)] string pszLabel);
            void GetResult(out IShellItem ppsi);
            void AddPlace([In] IShellItem psi, int alignment);
            void SetDefaultExtension([In, MarshalAs(UnmanagedType.LPWStr)] string pszDefaultExtension);
            void Close([MarshalAs(UnmanagedType.Error)] int hr);
            void SetClientGuid([In] ref Guid guid);
            void ClearClientData();
            void SetFilter([MarshalAs(UnmanagedType.Interface)] IntPtr pFilter);
        }

        [ComImport]
        [Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE")]
        [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IShellItem {
            void BindToHandler([In] IntPtr pbc, [In] ref Guid bhid, [In] ref Guid riid, out IntPtr ppv);
            void GetParent(out IShellItem ppsi);
            void GetDisplayName([In] uint sigdnName, out IntPtr ppszName);
            void GetAttributes([In] uint sfgaoMask, out uint psfgaoAttribs);
            void Compare([In] IShellItem psi, [In] uint hint, out int piOrder);
        }

        private const uint FOS_PICKFOLDERS = 0x00000020;
        private const uint FOS_FORCEFILESYSTEM = 0x00000040;
        private const uint FOS_PATHMUSTEXIST = 0x00000800;
        private const uint SIGDN_FILESYSPATH = 0x80058000;

        public static string SelectFolder(string title, string okButtonLabel, string folderLabel) {
            var dialog = (IFileDialog)new FileOpenDialogRCW();
            uint options;
            dialog.GetOptions(out options);
            options |= FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST;
            dialog.SetOptions(options);
            if (!string.IsNullOrEmpty(title)) {
                dialog.SetTitle(title);
            }
            if (!string.IsNullOrEmpty(okButtonLabel)) {
                dialog.SetOkButtonLabel(okButtonLabel);
            }
            if (!string.IsNullOrEmpty(folderLabel)) {
                dialog.SetFileNameLabel(folderLabel);
            }
            int hr = dialog.Show(IntPtr.Zero);
            if (hr != 0) {
                return null;
            }
            IShellItem item;
            dialog.GetResult(out item);
            IntPtr psz;
            item.GetDisplayName(SIGDN_FILESYSPATH, out psz);
            string path = Marshal.PtrToStringUni(psz);
            Marshal.FreeCoTaskMem(psz);
            return path;
        }
    }
}
'@
}

if ($Mode -eq 'save') {
    $path = [PassAi.ModernFolderPicker]::SelectFolder('Enregistrer sous', 'Enregistrer', 'Dossier du projet :')
} else {
    $path = [PassAi.ModernFolderPicker]::SelectFolder('Select the real project folder for PASS AI', 'Selectionner un dossier', 'Dossier :')
}
if ($path) {
    Write-Output $path
}
