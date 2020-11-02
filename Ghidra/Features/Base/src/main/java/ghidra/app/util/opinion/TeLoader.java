/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.util.opinion;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import generic.continues.GenericFactory;
import generic.continues.RethrowContinuesFactory;
import ghidra.app.util.MemoryBlockUtil;
import ghidra.app.util.Option;
import ghidra.app.util.bin.*;
import ghidra.app.util.bin.format.te.*;
import ghidra.app.util.bin.format.te.TerseExecutable.SectionLayout;
import ghidra.app.util.bin.format.te.debug.DebugCOFFSymbol;
import ghidra.app.util.bin.format.te.debug.DebugDirectoryParser;
import ghidra.app.util.demangler.*;
import ghidra.app.util.importer.*;
import ghidra.framework.model.DomainObject;
import ghidra.framework.options.Options;
import ghidra.program.model.address.*;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.reloc.RelocationTable;
import ghidra.program.model.symbol.*;
import ghidra.program.model.util.AddressSetPropertyMap;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.util.*;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;
import ghidra.util.task.TaskMonitor;

/**
 * UEFI Terse Executable (TE) loader.
 */
public class TeLoader extends AbstractTeDebugLoader {

	/** The name of the TE loader */
	public final static String TE_NAME = "Terse Executable (TE)";

	/** The name of the TE headers memory block. */
	public static final String HEADERS = "Headers";

	/** The minimum length a file has to be for it to qualify as a possible TE. */
	private static final long MIN_BYTE_LENGTH = 4;

	@Override
	public Collection<LoadSpec> findSupportedLoadSpecs(ByteProvider provider) throws IOException {
		List<LoadSpec> loadSpecs = new ArrayList<>();

		if (provider.length() < MIN_BYTE_LENGTH) {
			return loadSpecs;
		}

		TerseExecutable te = TerseExecutable.createTerseExecutable(
			RethrowContinuesFactory.INSTANCE, provider, SectionLayout.FILE, false, false);
		TEHeader teHeader = te.getTEHeader();
		if (teHeader != null) {
			long imageBase = teHeader.getImageBase();
			String machineName = teHeader.getMachineName();
			String compiler = CompilerOpinion.stripFamily(CompilerOpinion.getOpinion(te, provider));
			for (QueryResult result : QueryOpinionService.query(getName(), machineName, compiler)) {
				loadSpecs.add(new LoadSpec(this, imageBase, result));
			}
			if (loadSpecs.isEmpty()) {
				loadSpecs.add(new LoadSpec(this, imageBase, true));
			}
		}

		return loadSpecs;
	}

	@Override
	protected void load(ByteProvider provider, LoadSpec loadSpec, List<Option> options,
			Program program, MemoryConflictHandler handler, TaskMonitor monitor, MessageLog log)
			throws IOException {

		if (monitor.isCancelled()) {
			return;
		}

		GenericFactory factory = MessageLogContinuesFactory.create(log);
		TerseExecutable te = TerseExecutable.createTerseExecutable(factory, provider,
			SectionLayout.FILE, false, shouldParseCliHeaders(options));

		TEHeader teHeader = te.getTEHeader();
		if (teHeader == null) {
			return;
		}

		monitor.setMessage("Completing TE header parsing...");

		try {
			Map<Integer, Address> sectionNumberToAddress =
				processMemoryBlocks(te, program, handler, monitor, log);

			monitor.setCancelEnabled(false);
			teHeader.processDataDirectories(monitor);
			monitor.setCancelEnabled(true);
			teHeader.validateDataDirectories(program);

			DataDirectory[] datadirs = teHeader.getDataDirectories();
			layoutHeaders(program, te, teHeader, datadirs);
			for (DataDirectory datadir : datadirs) {
				if (datadir == null || !datadir.hasParsedCorrectly()) {
					continue;
				}
				if (datadir.hasParsedCorrectly()) {
					datadir.markup(program, false, monitor, log, teHeader);
				}
			}

			processExports(teHeader, program, monitor, log);
			processImports(teHeader, program, monitor, log);
			processRelocations(teHeader, program, monitor, log);
			processDebug(teHeader, sectionNumberToAddress, program, monitor);
			processProperties(teHeader, program, monitor);
			processComments(program.getListing(), monitor);
			processSymbols(teHeader, sectionNumberToAddress, program, monitor, log);

			processEntryPoints(teHeader, program, monitor);
			String compiler = CompilerOpinion.getOpinion(te, provider).toString();
			program.setCompiler(compiler);

		}
		catch (AddressOverflowException e) {
			throw new IOException(e);
		}
		catch (DuplicateNameException e) {
			throw new IOException(e);
		}
		catch (CodeUnitInsertionException e) {
			throw new IOException(e);
		}
		catch (DataTypeConflictException e) {
			throw new IOException(e);
		}
		catch (MemoryAccessException e) {
			throw new IOException(e);
		}
		monitor.setMessage(program.getName() + ": done!");
	}

	@Override
	protected boolean isCaseInsensitiveLibraryFilenames() {
		return true;
	}

	private void layoutHeaders(Program program, TerseExecutable te, TEHeader teHeader,
			DataDirectory[] datadirs) {
		try {
			DataType dt = te.toDataType();
			Address start = program.getImageBase();
			DataUtilities.createData(program, start, dt, -1, false,
				DataUtilities.ClearDataMode.CHECK_FOR_SPACE);

			SectionHeader[] sections = te.getSectionHeaders();
			int index = fh.getPointerToSections();
			start = program.getImageBase().add(index);
			for (SectionHeader section : sections) {
				dt = section.toDataType();
				DataUtilities.createData(program, start, dt, -1, false,
					DataUtilities.ClearDataMode.CHECK_FOR_SPACE);
				setComment(CodeUnit.EOL_COMMENT, start, section.getName());
				start = start.add(dt.getLength());
			}

//			for (int i = 0; i < datadirs.length; ++i) {
//				if (datadirs[i] == null || datadirs[i].getSize() == 0) {
//					continue;
//				}
//
//				if (datadirs[i].hasParsedCorrectly()) {
//					start = datadirs[i].getMarkupAddress(program, true);
//					dt = datadirs[i].toDataType();
//					DataUtilities.createData(program, start, dt, true, DataUtilities.ClearDataMode.CHECK_FOR_SPACE);
//				}
//			}
		}
		catch (Exception e1) {
			Msg.error(this, "Error laying down header structures " + e1);
		}
	}

	private void processSymbols(FileHeader fileHeader, Map<Integer, Address> sectionNumberToAddress,
			Program program, TaskMonitor monitor, MessageLog log) {
		List<DebugCOFFSymbol> symbols = fileHeader.getSymbols();
		int errorCount = 0;
		for (DebugCOFFSymbol symbol : symbols) {
			if (!processDebugCoffSymbol(symbol, sectionNumberToAddress, program, monitor)) {
				++errorCount;
			}
		}

		if (errorCount != 0) {
			log.appendMsg(
				"Failed to apply " + errorCount + " symbols contained within unknown sections.");
		}
	}

	private void processProperties(TeHeader teHeader, Program prog,
			TaskMonitor monitor) {
		if (monitor.isCancelled()) {
			return;
		}
		Options props = prog.getOptions(Program.PROGRAM_INFO);
		props.setInt("SectionAlignment", teHeader.getSectionAlignment());
		props.setBoolean(RelocationTable.RELOCATABLE_PROP_NAME,
			prog.getRelocationTable().getSize() > 0);
	}

	private void processRelocations(TeHeader teHeader, Program prog,
			TaskMonitor monitor, MessageLog log) {

		if (monitor.isCancelled()) {
			return;
		}
		monitor.setMessage(prog.getName() + ": processing relocation tables...");

		DataDirectory[] dataDirectories = teHeader.getDataDirectories();
		if (dataDirectories.length <= TeHeader.IMAGE_DIRECTORY_ENTRY_BASERELOC) {
			return;
		}
		BaseRelocationDataDirectory brdd =
			(BaseRelocationDataDirectory) dataDirectories[TeHeader.IMAGE_DIRECTORY_ENTRY_BASERELOC];
		if (brdd == null) {
			return;
		}

		AddressSpace space = prog.getAddressFactory().getDefaultAddressSpace();
		RelocationTable relocTable = prog.getRelocationTable();

		Memory memory = prog.getMemory();

		BaseRelocation[] relocs = brdd.getBaseRelocations();
		long originalImageBase = teHeader.getOriginalImageBase();
		AddressRange brddRange =
			new AddressRangeImpl(space.getAddress(originalImageBase + brdd.getVirtualAddress()),
				space.getAddress(originalImageBase + brdd.getVirtualAddress() + brdd.getSize()));
		AddressRange headerRange = new AddressRangeImpl(space.getAddress(originalImageBase),
			space.getAddress(originalImageBase + teHeader.getSizeOfHeaders()));
		DataConverter conv = new LittleEndianDataConverter();

		for (BaseRelocation reloc : relocs) {
			if (monitor.isCancelled()) {
				return;
			}
			int baseAddr = reloc.getVirtualAddress();
			int count = reloc.getCount();
			for (int j = 0; j < count; ++j) {
				int type = reloc.getType(j);
				if (type == BaseRelocation.IMAGE_REL_BASED_ABSOLUTE) {
					continue;
				}
				int offset = reloc.getOffset(j);
				long addr = Conv.intToLong(baseAddr + offset) + teHeader.getImageBase();
				Address relocAddr = space.getAddress(addr);

				try {
					byte[] bytes = teHeader.is64bit() ? new byte[8] : new byte[4];
					memory.getBytes(relocAddr, bytes);
					if (teHeader.wasRebased()) {
						long val = teHeader.is64bit() ? conv.getLong(bytes)
								: conv.getInt(bytes) & 0xFFFFFFFFL;
						val =
							val - (originalImageBase & 0xFFFFFFFFL) + teHeader.getImageBase();
						byte[] newbytes = teHeader.is64bit() ? conv.getBytes(val)
								: conv.getBytes((int) val);
						if (type == BaseRelocation.IMAGE_REL_BASED_HIGHLOW) {
							memory.setBytes(relocAddr, newbytes);
						}
						else if (type == BaseRelocation.IMAGE_REL_BASED_DIR64) {
							memory.setBytes(relocAddr, newbytes);
						}
						else {
							Msg.error(this, "Non-standard relocation type " + type);
						}
					}

					relocTable.add(relocAddr, type, null, bytes, null);

				}
				catch (MemoryAccessException e) {
					log.appendMsg("Relocation does not exist in memory: " + relocAddr);
				}
				if (brddRange.contains(relocAddr)) {
					Msg.error(this, "Self-modifying relocation table at " + relocAddr);
					return;
				}
				if (headerRange.contains(relocAddr)) {
					Msg.error(this, "Header modified at " + relocAddr);
					return;
				}
			}
		}
	}

	private void processImports(TeHeader teHeader, Program program, TaskMonitor monitor,
			MessageLog log) {

		if (monitor.isCancelled()) {
			return;
		}
		monitor.setMessage(program.getName() + ": processing imports...");

		DataDirectory[] dataDirectories = teHeader.getDataDirectories();
		if (dataDirectories.length <= TeHeader.IMAGE_DIRECTORY_ENTRY_IMPORT) {
			return;
		}
		ImportDataDirectory idd =
			(ImportDataDirectory) dataDirectories[TeHeader.IMAGE_DIRECTORY_ENTRY_IMPORT];
		if (idd == null) {
			return;
		}

		AddressFactory af = program.getAddressFactory();
		AddressSpace space = af.getDefaultAddressSpace();

		Listing listing = program.getListing();
		ReferenceManager refManager = program.getReferenceManager();

		ImportInfo[] imports = idd.getImports();
		for (ImportInfo importInfo : imports) {
			if (monitor.isCancelled()) {
				return;
			}

			long addr = Conv.intToLong(importInfo.getAddress()) + teHeader.getImageBase();

			//If not 64bit make sure address is not larger
			//than 32bit. On WindowsCE some sections are
			//declared to roll over.
			if (!teHeader.is64bit()) {
				addr &= Conv.INT_MASK;
			}

			Address address = space.getAddress(addr);

			setComment(CodeUnit.PRE_COMMENT, address, importInfo.getComment());

			Data data = listing.getDefinedDataAt(address);
			if (data == null || !(data.getValue() instanceof Address)) {
				continue;
			}

			Address extAddr = (Address) data.getValue();
			if (extAddr != null) {
				// remove the existing mem reference that was created
				// when making a pointer
				data.removeOperandReference(0, extAddr);
//	            symTable.removeSymbol(symTable.getDynamicSymbol(extAddr));

				try {
					refManager.addExternalReference(address, importInfo.getDLL().toUpperCase(),
						importInfo.getName(), extAddr, SourceType.IMPORTED, 0, RefType.DATA);
				}
				catch (DuplicateNameException e) {
					log.appendMsg("External location not created: " + e.getMessage());
				}
				catch (InvalidInputException e) {
					log.appendMsg("External location not created: " + e.getMessage());
				}
			}
		}
	}

	/**
	 * Mark this location as code in the CodeMap.
	 * The analyzers will pick this up and disassemble the code.
	 *
	 * TODO: this should be in a common place, so all importers can communicate that something
	 * is code or data.
	 *
	 * @param program The program to mark up.
	 * @param address The location.
	 */
	private void markAsCode(Program program, Address address) {
		AddressSetPropertyMap codeProp = program.getAddressSetPropertyMap("CodeMap");
		if (codeProp == null) {
			try {
				codeProp = program.createAddressSetPropertyMap("CodeMap");
			}
			catch (DuplicateNameException e) {
				codeProp = program.getAddressSetPropertyMap("CodeMap");
			}
		}

		if (codeProp != null) {
			codeProp.add(address, address);
		}
	}

	private void processExports(TeHeader teHeader, Program program, TaskMonitor monitor,
			MessageLog log) {

		if (monitor.isCancelled()) {
			return;
		}
		monitor.setMessage(program.getName() + ": processing exports...");

		DataDirectory[] dataDirectories = teHeader.getDataDirectories();
		if (dataDirectories.length <= TeHeader.IMAGE_DIRECTORY_ENTRY_EXPORT) {
			return;
		}
		ExportDataDirectory edd =
			(ExportDataDirectory) dataDirectories[TeHeader.IMAGE_DIRECTORY_ENTRY_EXPORT];

		if (edd == null) {
			return;
		}

		AddressFactory af = program.getAddressFactory();
		AddressSpace space = af.getDefaultAddressSpace();
		SymbolTable symTable = program.getSymbolTable();
		Memory memory = program.getMemory();
		Listing listing = program.getListing();
		ReferenceManager refManager = program.getReferenceManager();

		ExportInfo[] exports = edd.getExports();
		for (ExportInfo export : exports) {
			if (monitor.isCancelled()) {
				return;
			}

			Address address = space.getAddress(export.getAddress());
			setComment(CodeUnit.PRE_COMMENT, address, export.getComment());
			symTable.addExternalEntryPoint(address);

			String name = export.getName();
			try {
				symTable.createLabel(address, name, SourceType.IMPORTED);
			}
			catch (InvalidInputException e) {
				// Don't create invalid symbol
			}

			DemangledObject demangledObj = null;
			try {
				demangledObj = DemanglerUtil.demangle(program, name);
			}
			catch (Exception e) {
				//log.appendMsg("Unable to demangle: "+name);
			}
			if (demangledObj != null) {
				String comment = demangledObj.getSignature(true);
				if (hasComment(CodeUnit.PLATE_COMMENT, address)) {
					comment = "\n" + comment;
				}
				setComment(CodeUnit.PLATE_COMMENT, address, comment);
			}

			try {
				symTable.createLabel(address, SymbolUtilities.ORDINAL_PREFIX + export.getOrdinal(),
					SourceType.IMPORTED);
			}
			catch (InvalidInputException e) {
				// Don't create invalid symbol
			}

			// When exported symbol is a forwarder,
			// a string exists at the address of the export
			// Therefore, create a string data object to prevent
			// disassembler from attempting to create
			// code here. If code was created, it would be incorrect
			// and offcut.
			if (export.isForwarded()) {
				try {
					listing.createData(address, TerminatedStringDataType.dataType, -1);
					Data data = listing.getDataAt(address);
					if (data != null) {
						Object obj = data.getValue();
						if (obj instanceof String) {
							String str = (String) obj;
							int dotpos = str.indexOf('.');

							if (dotpos < 0) {
								dotpos = 0;//TODO
							}

							// get the name of the dll
							String dllName = str.substring(0, dotpos) + ".dll";

							// get the name of the symbol
							String expName = str.substring(dotpos + 1);

							try {
								refManager.addExternalReference(address, dllName.toUpperCase(),
									expName, null, SourceType.IMPORTED, 0, RefType.DATA);
							}
							catch (DuplicateNameException e) {
								log.appendMsg("External location not created: " + e.getMessage());
							}
							catch (InvalidInputException e) {
								log.appendMsg("External location not created: " + e.getMessage());
							}
						}
					}
				}
				catch (CodeUnitInsertionException e) {
					// Nothing to do...just continue on
				}
			}

			//if this export is not in an executable section,
			//then it is a DATA export.
			//see if it is a pointer, otherwise make it an undefined1
			MemoryBlock block = memory.getBlock(address);
			if (block != null && !block.isExecute()) {
				try {
					if (demangledObj instanceof DemangledVariable) {
						DemangledVariable demangledVar = (DemangledVariable) demangledObj;
						DemangledDataType ddt = demangledVar.getDataType();
						DataType dt =
							ddt == null ? null : ddt.getDataType(program.getDataTypeManager());
						if (dt != null && dt.getLength() > 0) {
							listing.createData(address, dt);
						}
						else {
							listing.createData(address, new Undefined1DataType());
						}
					}
					else {
						listing.createData(address, StructConverter.POINTER,
							address.getPointerSize());
						Data data = listing.getDataAt(address);
						Address ptr = data.getAddress(0);
						if (ptr == null || !memory.contains(ptr)) {
							listing.clearCodeUnits(data.getMinAddress(), data.getMaxAddress(),
								false);
							listing.createData(address, new Undefined1DataType());
						}
					}
				}
				catch (DataTypeConflictException | CodeUnitInsertionException e) {
					// Nothing to do...just continue on
				}
			}
		}
	}

	private Map<Integer, Address> processMemoryBlocks(TerseExecutable te, Program prog,
			MemoryConflictHandler handler, TaskMonitor monitor, MessageLog log)
			throws AddressOverflowException, IOException {

		AddressFactory af = prog.getAddressFactory();
		AddressSpace space = af.getDefaultAddressSpace();
		Map<Integer, Address> sectionNumberToAddress = new HashMap<>();

		if (monitor.isCancelled()) {
			return sectionNumberToAddress;
		}
		monitor.setMessage(prog.getName() + ": processing memory blocks...");

		NTHeader ntHeader = te.getNTHeader();
		FileHeader fileHeader = ntHeader.getFileHeader();
		TeHeader teHeader = ntHeader.getTeHeader();

		MemoryBlockUtil mbu = new MemoryBlockUtil(prog, handler);

		SectionHeader[] sections = fileHeader.getSectionHeaders();
		if (sections.length == 0) {
			Msg.warn(this, "No sections found");
		}

		// Header block
		try {
			int virtualSize = getVirtualSize(pe, sections, space);
			long addr = teHeader.getImageBase();
			Address address = space.getAddress(addr);

			boolean r = true;
			boolean w = false;
			boolean x = false;

			try (InputStream dataStream = fileHeader.getDataStream()) {
				mbu.createInitializedBlock(HEADERS, address, dataStream, virtualSize, "", "", r, w,
					x, monitor);
			}
		}
		finally {
			log.appendMsg(mbu.getMessages());
			mbu.dispose();
			mbu = null;
		}

		mbu = new MemoryBlockUtil(prog, handler);

		// Section blocks
		try {
			for (int i = 0; i < sections.length; ++i) {
				if (monitor.isCancelled()) {
					return sectionNumberToAddress;
				}

				long addr = sections[i].getVirtualAddress() + teHeader.getImageBase();

				Address address = space.getAddress(addr);

				boolean r = ((sections[i].getCharacteristics() &
					SectionFlags.IMAGE_SCN_MEM_READ.getMask()) != 0x0);
				boolean w = ((sections[i].getCharacteristics() &
					SectionFlags.IMAGE_SCN_MEM_WRITE.getMask()) != 0x0);
				boolean x = ((sections[i].getCharacteristics() &
					SectionFlags.IMAGE_SCN_MEM_EXECUTE.getMask()) != 0x0);

				int rawDataSize = sections[i].getSizeOfRawData();
				int virtualSize = sections[i].getVirtualSize();
				if (rawDataSize != 0) {
					try (InputStream dataStream = sections[i].getDataStream()) {
						int dataSize =
							((rawDataSize > virtualSize && virtualSize > 0) || rawDataSize < 0)
									? virtualSize : rawDataSize;
						if (ntHeader.checkRVA(dataSize) ||
							(0 < dataSize && dataSize < te.getFileLength())) {
							if (!ntHeader.checkRVA(dataSize)) {
								Msg.warn(this, "TeHeader.SizeOfImage < size of " +
									sections[i].getName() + " section");
							}
							mbu.createInitializedBlock(sections[i].getReadableName(), address,
								dataStream, dataSize, "", "", r, w, x, monitor);

							sectionNumberToAddress.put(i + 1, address);
						}
					}
					if (rawDataSize == virtualSize) {
						continue;
					}
					else if (rawDataSize > virtualSize) {
						// virtual size fully initialized
						continue;
					}
					// remainder of virtual size is uninitialized
					if (rawDataSize < 0) {
						Msg.error(this,
							"Section[" + i + "] has invalid size " +
								Integer.toHexString(rawDataSize) + " (" +
								Integer.toHexString(virtualSize) + ")");
						break;
					}
					virtualSize -= rawDataSize;
					address = address.add(rawDataSize);
				}

				if (virtualSize == 0) {
					Msg.error(this, "Section[" + i + "] has size zero");
				}
				else {
					int dataSize = (virtualSize > 0 || rawDataSize < 0) ? virtualSize : 0;
					if (dataSize > 0) {
						mbu.createUninitializedBlock(false, sections[i].getReadableName(), address,
							dataSize, "", "", r, w, x);
						sectionNumberToAddress.put(i + 1, address);
					}
				}

			}
		}
		catch (IllegalStateException ise) {
			if (teHeader.getFileAlignment() != teHeader.getSectionAlignment()) {
				throw new IllegalStateException(ise);
			}
			Msg.warn(this, "Section header processing aborted");
		}
		finally {
			log.appendMsg(mbu.getMessages());
			mbu.dispose();
			mbu = null;
		}

		return sectionNumberToAddress;
	}

	private int getVirtualSize(TerseExecutable te, SectionHeader[] sections,
			AddressSpace space) {
		DOSHeader dosHeader = te.getDOSHeader();
		TeHeader teHeader = te.getNTHeader().getTeHeader();
		int virtualSize = teHeader.is64bit() ? Constants.IMAGE_SIZEOF_NT_OPTIONAL64_HEADER
				: Constants.IMAGE_SIZEOF_NT_OPTIONAL32_HEADER;
		virtualSize += FileHeader.IMAGE_SIZEOF_FILE_HEADER + 4;
		virtualSize += dosHeader.e_lfanew();
		if (teHeader.getSizeOfHeaders() > virtualSize) {
			virtualSize = (int) teHeader.getSizeOfHeaders();
		}

		if (teHeader.getFileAlignment() == teHeader.getSectionAlignment()) {
			if (teHeader.getFileAlignment() <= 0x800) {
				Msg.warn(this,
					"File and section alignments identical - possible driver or sectionless image");
			}
		}
		//long max = space.getMaxAddress().getOffset() - teHeader.getImageBase();
		//if (virtualSize > max) {
		//	virtualSize = (int) max;
		//	Msg.error(this, "Possible truncation of image at "+Long.toHexString(teHeader.getImageBase()));
		//}
		return virtualSize;
	}

	private void processEntryPoints(NTHeader ntHeader, Program prog, TaskMonitor monitor) {
		if (monitor.isCancelled()) {
			return;
		}
		monitor.setMessage(prog.getName() + ": processing entry points...");

		TeHeader teHeader = ntHeader.getTeHeader();
		AddressFactory af = prog.getAddressFactory();
		AddressSpace space = af.getDefaultAddressSpace();
		SymbolTable symTable = prog.getSymbolTable();

		long entry = teHeader.getAddressOfEntryPoint();
		int ptr = ntHeader.rvaToPointer((int) entry);
		if (ptr < 0) {
			if (entry != 0 ||
				(ntHeader.getFileHeader().getCharacteristics() & FileHeader.IMAGE_FILE_DLL) == 0) {
				Msg.warn(this, "Virtual entry point at " + Long.toHexString(entry));
			}
		}
		Address baseAddr = space.getAddress(entry);
		long imageBase = teHeader.getImageBase();
		Address entryAddr = baseAddr.addWrap(imageBase);
		entry += teHeader.getImageBase();
		try {
			symTable.createLabel(entryAddr, "entry", SourceType.IMPORTED);
			markAsCode(prog, entryAddr);
		}
		catch (InvalidInputException e) {
			// ignore
		}
		symTable.addExternalEntryPoint(entryAddr);
	}

	private void processDebug(TeHeader teHeader,
			Map<Integer, Address> sectionNumberToAddress, Program program, TaskMonitor monitor) {
		if (monitor.isCancelled()) {
			return;
		}
		monitor.setMessage(program.getName() + ": processing debug information...");

		DataDirectory[] dataDirectories = teHeader.getDataDirectories();
		if (dataDirectories.length <= TeHeader.IMAGE_DIRECTORY_ENTRY_DEBUG) {
			return;
		}
		DebugDataDirectory ddd =
			(DebugDataDirectory) dataDirectories[TeHeader.IMAGE_DIRECTORY_ENTRY_DEBUG];

		if (ddd == null) {
			return;
		}

		DebugDirectoryParser parser = ddd.getParser();
		if (parser == null) {
			return;
		}

		processDebug(parser, sectionNumberToAddress, program, monitor);
	}

	@Override
	public String getName() {
		return PE_NAME;
	}

	public static class CompilerOpinion {
		static final char[] errString_borland =
			"This program must be run under Win32\r\n$".toCharArray();
		static final char[] errString_GCC_VS =
			"This program cannot be run in DOS mode.\r\r\n$".toCharArray();
		static final int[] asm16_Borland = { 0xBA, 0x10, 0x00, 0x0E, 0x1F, 0xB4, 0x09, 0xCD, 0x21,
			0xB8, 0x01, 0x4C, 0xCD, 0x21, 0x90, 0x90 };
		static final int[] asm16_GCC_VS =
			{ 0x0e, 0x1f, 0xba, 0x0e, 0x00, 0xb4, 0x09, 0xcd, 0x21, 0xb8, 0x01, 0x4c, 0xcd, 0x21 };

		public enum CompilerEnum {

			VisualStudio("visualstudio:unknown"),
			GCC("gcc:unknown"),
			GCC_VS("visualstudiogcc"),
			BorlandPascal("borland:pascal"),
			BorlandCpp("borland:c++"),
			BorlandUnk("borland:unknown"),
			CLI("cli"),
			Unknown("unknown");

			private String label;

			private CompilerEnum(String label) {
				this.label = label;
			}

			@Override
			public String toString() {
				return label;
			}
		}

		// Treat string as upto 3 colon separated fields describing a compiler  --   <product>:<language>:version
		public static String stripFamily(CompilerEnum val) {
			if (val == CompilerEnum.BorlandCpp) {
				return "borlandcpp";
			}
			if (val == CompilerEnum.BorlandPascal) {
				return "borlanddelphi";
			}
			if (val == CompilerEnum.BorlandUnk) {
				return "borlandcpp";
			}
			String compilerid = val.toString();
			int colon = compilerid.indexOf(':');
			if (colon > 0) {
				return compilerid.substring(0, colon);
			}
			return compilerid;
		}

		private static SectionHeader getSectionHeader(String name, SectionHeader[] list) {
			for (SectionHeader element : list) {
				if (element.getName().equals(name)) {
					return element;
				}
			}
			return null;
		}

		/**
		 * Return true if chararray appears in full, starting at offset bytestart in bytearray
		 * @param bytearray the array of bytes containing the potential match
		 * @param bytestart the potential start of the match
		 * @param chararray the array of characters to match
		 * @return true if there is a full match
		 */
		private static boolean compareBytesToChars(byte[] bytearray, int bytestart,
				char[] chararray) {
			int i = 0;
			if (bytestart + chararray.length < bytearray.length) {
				for (; i < chararray.length; ++i) {
					if (chararray[i] != (char) bytearray[bytestart + i]) {
						break;
					}
				}
			}
			return (i == chararray.length);
		}

		public static CompilerEnum getOpinion(TerseExecutable te, ByteProvider provider)
				throws IOException {
			CompilerEnum compilerType = CompilerEnum.Unknown;
			CompilerEnum offsetChoice = CompilerEnum.Unknown;
			CompilerEnum asmChoice = CompilerEnum.Unknown;
			CompilerEnum errStringChoice = CompilerEnum.Unknown;
			BinaryReader br = new BinaryReader(provider, true);

			DOSHeader dh = te.getDOSHeader();

			// Check for managed code (.NET)
			if (te.getNTHeader().getTeHeader().isCLI()) {
				return CompilerEnum.CLI;
			}

			// Determine based on PE Header offset
			if (dh.e_lfanew() == 0x80) {
				offsetChoice = CompilerEnum.GCC_VS;
			}
			else if (dh.e_lfanew() < 0x80) {
				offsetChoice = CompilerEnum.Unknown;
			}
			else {

				// Check for "DanS"
				int val1 = br.readInt(0x80);
				int val2 = br.readInt(0x80 + 4);

				if (val1 != 0 && val2 != 0 && (val1 ^ val2) == 0x536e6144) {
					compilerType = CompilerEnum.VisualStudio;
					return compilerType;
				}
				else if (dh.e_lfanew() == 0x100) {
					offsetChoice = CompilerEnum.BorlandPascal;
				}
				else if (dh.e_lfanew() == 0x200) {
					offsetChoice = CompilerEnum.BorlandCpp;
				}
				else if (dh.e_lfanew() > 0x300) {
					compilerType = CompilerEnum.Unknown;
					return compilerType;
				}
				else {
					offsetChoice = CompilerEnum.Unknown;
				}
			} // End PE header offset check

			int counter;
			byte[] asm = provider.readBytes(0x40, 256);
			for (counter = 0; counter < asm16_Borland.length; counter++) {
				if ((asm[counter] & 0xff) != (asm16_Borland[counter] & 0xff)) {
					break;
				}
			}
			if (counter == asm16_Borland.length) {
				asmChoice = CompilerEnum.BorlandUnk;
			}
			else {
				for (counter = 0; counter < asm16_GCC_VS.length; counter++) {
					if ((asm[counter] & 0xff) != (asm16_GCC_VS[counter] & 0xff)) {
						break;
					}
				}
				if (counter == asm16_GCC_VS.length) {
					asmChoice = CompilerEnum.GCC_VS;
				}
				else {
					asmChoice = CompilerEnum.Unknown;
				}
			}
			// Check for error message
			int errStringOffset = -1;
			for (int i = 10; i < asm.length - 3; i++) {
				if (asm[i] == 'T' && asm[i + 1] == 'h' && asm[i + 2] == 'i' && asm[i + 3] == 's') {
					errStringOffset = i;
				}
			}

			if (errStringOffset == -1) {
				asmChoice = CompilerEnum.Unknown;
			}
			else {
				if (compareBytesToChars(asm, errStringOffset, errString_borland)) {
					errStringChoice = CompilerEnum.BorlandUnk;
					if (offsetChoice == CompilerEnum.BorlandCpp ||
						offsetChoice == CompilerEnum.BorlandPascal) {
						compilerType = offsetChoice;
						return compilerType;
					}
				}
				else if (compareBytesToChars(asm, errStringOffset, errString_GCC_VS)) {
					errStringChoice = CompilerEnum.GCC_VS;
				}
				else {
					errStringChoice = CompilerEnum.Unknown;
				}
			}

			// Check for AddressOfStart and PointerToSymbol
			if (errStringChoice == CompilerEnum.GCC_VS && asmChoice == CompilerEnum.GCC_VS &&
				dh.e_lfanew() == 0x80) {
				// Trying to determine if we have gcc or old VS

				// Look for the "Visual Studio" library identifier
//				if (mem.findBytes(mem.getMinAddress(), "Visual Studio".getBytes(),
//						null, true, monitor) != null) {
//					compilerType = COMPIL_VS;
//					return compilerType;
//				}

				// Now look for offset to code (0x1000 for gcc) and PointerToSymbols
				// (0 for VS, non-zero for gcc)
				int addrCode = br.readInt(dh.e_lfanew() + 40);
				if (addrCode != 0x1000) {
					compilerType = CompilerEnum.VisualStudio;
					return compilerType;
				}

				int ptrSymTable = br.readInt(dh.e_lfanew() + 12);
				if (ptrSymTable != 0) {
					compilerType = CompilerEnum.GCC;
					return compilerType;
				}
			}
			else if (errStringChoice == CompilerEnum.Unknown || asmChoice == CompilerEnum.Unknown) {
				compilerType = CompilerEnum.Unknown;
				return compilerType;
			}

			if (errStringChoice == CompilerEnum.BorlandUnk ||
				asmChoice == CompilerEnum.BorlandUnk) {
				// Pretty sure it's Borland, but didn't get 0x100 or 0x200
				compilerType = CompilerEnum.BorlandUnk;
				return compilerType;
			}

			if ((offsetChoice == CompilerEnum.GCC_VS) || (errStringChoice == CompilerEnum.GCC_VS)) {
				// Pretty sure it's either gcc or Visual Studio
				compilerType = CompilerEnum.GCC_VS;
			}
			else {
				// Not sure what it is
				compilerType = CompilerEnum.Unknown;
			}

			// Reaching this point implies that we did not find "DanS and we didn't
			// see the Borland DOS complaint
			boolean probablyNotVS = false;
			// TODO: See if we have an .idata segment and what type it is
			// Need to make sure that this is the right check to be making
			SectionHeader[] headers = te.getNTHeader().getFileHeader().getSectionHeaders();
			if (getSectionHeader(".idata", headers) != null) {
				probablyNotVS = true;
			}

			if (getSectionHeader("CODE", headers) != null) {
				compilerType = CompilerEnum.BorlandPascal;
				return compilerType;
			}

			SectionHeader segment = getSectionHeader(".bss", headers);
			if ((segment != null)/* && segment.getType() == BSS_TYPE */) {
				compilerType = CompilerEnum.GCC;
				return compilerType;
//			} else if (segment != null) {
//				compilerType = CompilerEnum.BorlandCpp;
//				return compilerType;
			}
			else if (!probablyNotVS) {
				compilerType = CompilerEnum.VisualStudio;
				return compilerType;
			}

			if (getSectionHeader(".tls", headers) != null) {
				// expect Borland - prefer cpp since CODE segment didn't occur
				compilerType = CompilerEnum.BorlandCpp;
			}

			return compilerType;
		}
	}
}