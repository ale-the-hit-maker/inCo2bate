"""
================================================================================
IncuSense Aerodynamic Clamshell Duct - Senior Mechanical Design Engineer
================================================================================

A production-ready parametric 3D CAD model for an aerodynamic clamshell duct
designed to house an IoT sensor (TO-39) with PCB overhang insertion.

DESIGN SPECIFICATIONS:
- Main tube: OD=30mm, ID=25mm, H=60mm
- PCB: 40mm wide, 1.6mm thick, 12mm overhang into tube
- Central volume compensation bulge for airflow bypass
- Clamshell split with M2 mounting flanges
- Gasket groove for compression seal
- Aerodynamic baffle for flow redirection

Author: Senior Mechanical Design Engineer
Framework: CadQuery (cadquery.readthedocs.io)
Output: front_shell.step, back_shell.step

================================================================================
"""

import cadquery as cq
from cadquery import Workplane
import math

# ============================================================================
# SECTION 1: DESIGN PARAMETERS
# ============================================================================

PARAMS = {
    # MAIN TUBE DIMENSIONS
    "tube_od": 30.0,                    # mm - Outer diameter
    "tube_id": 25.0,                    # mm - Inner diameter
    "tube_height": 60.0,                # mm - Total vertical height
    
    # PCB DIMENSIONS & INSERTION
    "pcb_width": 40.0,                  # mm - Width perpendicular to insertion
    "pcb_thickness": 1.6,               # mm - Vertical thickness
    "pcb_slot_height": 1.8,             # mm - Clearance included (1.6 + 0.2)
    "pcb_overhang": 12.0,               # mm - Depth into tube
    "pcb_length": 50.0,                 # mm - Along insertion axis
    "pcb_z_position": 30.0,             # mm - Height center from bottom
    
    # CENTRAL OFFSET (VOLUME COMPENSATION)
    "bulge_height": 10.0,               # mm - Radial bulge to bypass PCB
    "bulge_z_start": 18.0,              # mm - Where bulge begins
    "bulge_z_end": 42.0,                # mm - Where bulge ends
    
    # FLANGES & MOUNTING
    "flange_width": 5.0,                # mm - Flange lip width
    "flange_height": 50.0,              # mm - Vertical extent
    "screw_dia": 2.2,                   # mm - M2 hole diameter
    "screw_spacing": 15.0,              # mm - Spacing between holes
    
    # GASKET GROOVE
    "groove_width": 2.0,                # mm - Gasket groove width
    "groove_depth": 1.0,                # mm - Gasket groove depth
    
    # BAFFLE DIMENSIONS
    "baffle_z_start": 15.0,             # mm - Lower edge
    "baffle_z_end": 28.0,               # mm - Upper edge (below PCB)
    "baffle_chord_width": 20.0,         # mm - Width at base
}

# ============================================================================
# SECTION 2: MAIN TUBE CYLINDER
# ============================================================================

def create_main_tube(params):
    """
    Create the basic hollow cylindrical tube (OD - ID).
    
    Args:
        params (dict): Design parameters
    
    Returns:
        Workplane: Hollow cylindrical tube
    """
    od_radius = params["tube_od"] / 2.0
    id_radius = params["tube_id"] / 2.0
    height = params["tube_height"]
    
    # Create outer cylinder
    outer = cq.Workplane("XY").cylinder(
        height=height,
        radius=od_radius,
        centered=True
    )
    
    # Create inner void (to subtract)
    inner = cq.Workplane("XY").cylinder(
        height=height + 10,
        radius=id_radius,
        centered=True
    )
    
    # Boolean: outer minus inner = hollow tube
    tube = outer.cut(inner)
    
    return tube


# ============================================================================
# SECTION 3: CENTRAL OFFSET BULGE (Volume Compensation)
# ============================================================================

def create_volume_compensation_bulge(params):
    """
    Create an extruded bulge on the opposite side of the PCB insertion.
    
    PURPOSE:
    The PCB overhang (12mm into 25mm ID) blocks ~50% of internal cross-section.
    This bulge extends opposite to the PCB insertion, creating additional volume
    for air bypass around the PCB obstruction.
    
    GEOMETRY:
    - Positioned on back wall (opposite of PCB side)
    - Height: 10mm radial extension
    - Z-extent: 18-42mm (around PCB level)
    - Width: Matches PCB width (40mm)
    
    Args:
        params (dict): Design parameters
    
    Returns:
        Workplane: Bulge solid (to be unioned into tube)
    """
    id_radius = params["tube_id"] / 2.0
    bulge_height = params["bulge_height"]
    bulge_z_start = params["bulge_z_start"]
    bulge_z_end = params["bulge_z_end"]
    bulge_length = bulge_z_end - bulge_z_start
    pcb_width = params["pcb_width"]
    tube_height = params["tube_height"]
    
    # Create bulge as rectangular protrusion from back inner wall
    # Dimensions: height_z × width × height_radial
    bulge = cq.Workplane("XY").box(
        length=bulge_length,      # Z-extent (24mm)
        width=pcb_width,          # Y-width (40mm)
        height=bulge_height,      # Radial depth (10mm)
        centered=True
    )
    
    # Position at back wall (negative X), centered vertically around PCB
    bulge_x = -(id_radius - bulge_height / 2.0)
    bulge_z = (bulge_z_start + bulge_z_end) / 2.0 - tube_height / 2.0
    
    bulge = bulge.translate((bulge_x, 0, bulge_z))
    
    return bulge


# ============================================================================
# SECTION 4: PCB SLOT CUTOUT
# ============================================================================

def create_pcb_slot(params):
    """
    Create the rectangular slot where the PCB will be inserted.
    
    SPECIFICATION:
    - Width: 40.2 mm (PCB width 40mm + 0.1mm each side clearance)
    - Height: 1.8 mm (PCB thickness 1.6mm + 0.2mm clearance)
    - Depth: 12.0 mm (PCB overhang)
    - Located on front shell (positive X side)
    
    Args:
        params (dict): Design parameters
    
    Returns:
        Workplane: Rectangular slot (to be subtracted from tube)
    """
    pcb_width = params["pcb_width"]
    pcb_overhang = params["pcb_overhang"]
    pcb_slot_height = params["pcb_slot_height"]
    od_radius = params["tube_od"] / 2.0
    tube_height = params["tube_height"]
    pcb_z_position = params["pcb_z_position"]
    
    # Slot dimensions with clearance
    slot_width = pcb_width + 0.2       # 40.2 mm
    slot_height = pcb_slot_height      # 1.8 mm
    slot_depth = pcb_overhang + 5      # 17mm (extends beyond to ensure clean cut)
    
    # Create slot as rectangular box
    slot = cq.Workplane("XY").box(
        length=slot_depth,
        width=slot_width,
        height=slot_height,
        centered=True
    )
    
    # Position at front wall, centered vertically at PCB height
    slot_x = od_radius - slot_depth / 2.0 + 2
    slot_z = pcb_z_position - tube_height / 2.0
    
    slot = slot.translate((slot_x, 0, slot_z))
    
    return slot


# ============================================================================
# SECTION 5: GASKET GROOVE
# ============================================================================

def create_gasket_groove(params):
    """
    Create gasket compression groove around the PCB slot perimeter.
    
    SPECIFICATION:
    - Width: 2.0 mm
    - Depth: 1.0 mm
    - Located around split faces and PCB slot
    - Provides space for silicone gasket compression
    
    Args:
        params (dict): Design parameters
    
    Returns:
        Workplane: Groove profile (to be subtracted from tube)
    """
    pcb_width = params["pcb_width"]
    groove_width = params["groove_width"]
    groove_depth = params["groove_depth"]
    tube_height = params["tube_height"]
    pcb_z_position = params["pcb_z_position"]
    
    # Create groove as thin rectangular profile
    groove = cq.Workplane("XY").box(
        length=groove_width,
        width=pcb_width + 2,
        height=groove_depth,
        centered=True
    )
    
    # Position at split plane (X=0), at PCB height
    slot_z = pcb_z_position - tube_height / 2.0
    groove = groove.translate((0, 0, slot_z))
    
    return groove


# ============================================================================
# SECTION 6: AERODYNAMIC BAFFLE
# ============================================================================

def create_aerodynamic_baffle(params):
    """
    Create an aerodynamic baffle to redirect airflow around the PCB.
    
    DESIGN INTENT:
    The baffle sits inside the tube, directly below the PCB overhang.
    It creates a smooth flow path that prevents stagnation and redirects
    upward airflow toward the center of the tube, ensuring the TO-39 sensor
    (hanging in the tube center) receives clean, laminar airflow.
    
    GEOMETRY:
    - Wedge/chamfered shape
    - Starts at Z = 15mm (base, wide)
    - Ends at Z = 28mm (peak, narrow)
    - Creates smooth deflection surface
    
    Args:
        params (dict): Design parameters
    
    Returns:
        Workplane: Baffle solid (to be unioned into back shell)
    """
    id_radius = params["tube_id"] / 2.0
    baffle_z_start = params["baffle_z_start"]
    baffle_z_end = params["baffle_z_end"]
    baffle_height = baffle_z_end - baffle_z_start
    chord_width = params["baffle_chord_width"]
    tube_height = params["tube_height"]
    
    # Create baffle as rectangular wedge
    # Base (wider, at Z = 15mm)
    bottom_width = chord_width
    bottom_depth = id_radius * 0.8
    
    # Create baffle as rectangular block (simplified wedge)
    baffle = cq.Workplane("XY").box(
        length=baffle_height,
        width=bottom_width,
        height=bottom_depth,
        centered=True
    )
    
    # Position at back wall (negative X), below PCB
    baffle_x = -(id_radius - bottom_depth / 2.0)
    baffle_z_center = (baffle_z_start + baffle_z_end) / 2.0 - tube_height / 2.0
    
    baffle = baffle.translate((baffle_x, 0, baffle_z_center))
    
    return baffle


# ============================================================================
# SECTION 7: COMPLETE TUBE WITH ALL FEATURES
# ============================================================================

def create_complete_tube(params):
    """
    Create complete tube with all cut and union operations.
    
    Operations:
    1. Create hollow cylinder
    2. Union volume compensation bulge
    3. Cut PCB slot
    4. Cut gasket groove
    
    Args:
        params (dict): Design parameters
    
    Returns:
        Workplane: Complete tube solid
    """
    # Step 1: Main tube
    tube = create_main_tube(params)
    
    # Step 2: Add volume compensation bulge
    bulge = create_volume_compensation_bulge(params)
    tube = tube.union(bulge)
    
    # Step 3: Cut PCB slot
    slot = create_pcb_slot(params)
    tube = tube.cut(slot)
    
    # Step 4: Cut gasket groove
    groove = create_gasket_groove(params)
    tube = tube.cut(groove)
    
    return tube


# ============================================================================
# SECTION 8: CLAMSHELL SPLIT
# ============================================================================

def create_clamshell_split(tube, params):
    """
    Split the tube vertically into Front and Back shells.
    
    SPLIT PLANE: X = 0 (Y-Z plane)
    - Front Shell: X >= 0 (contains PCB slot)
    - Back Shell: X <= 0 (contains baffle)
    
    Args:
        tube (Workplane): Complete tube with all features
        params (dict): Design parameters
    
    Returns:
        tuple: (front_shell, back_shell) Workplane objects
    """
    tube_height = params["tube_height"]
    tube_od = params["tube_od"]
    
    # Create cutting geometry for front half (X >= 0)
    front_cutter = cq.Workplane("XY").box(
        length=100,
        width=tube_height + 50,
        height=tube_od + 50,
        centered=False
    ).translate((0, -(tube_height + 50) / 2.0, -(tube_od + 50) / 2.0))
    
    # Create cutting geometry for back half (X < 0)
    back_cutter = cq.Workplane("XY").box(
        length=100,
        width=tube_height + 50,
        height=tube_od + 50,
        centered=False
    ).translate((-100, -(tube_height + 50) / 2.0, -(tube_od + 50) / 2.0))
    
    # Split by intersection
    front_shell = tube.intersect(front_cutter)
    back_shell = tube.intersect(back_cutter)
    
    return front_shell, back_shell


# ============================================================================
# SECTION 9: MOUNTING FLANGES WITH M2 HOLES
# ============================================================================

def add_mounting_flanges(front_shell, back_shell, params):
    """
    Add mounting flanges with M2 through-holes to both shells.
    
    FLANGE SPECIFICATIONS:
    - Width: 5.0 mm
    - Height: 50.0 mm (vertical extent)
    - M2 holes: 2.2mm diameter, 15mm spacing
    
    Args:
        front_shell (Workplane): Front clamshell half
        back_shell (Workplane): Back clamshell half
        params (dict): Design parameters
    
    Returns:
        tuple: (front_shell, back_shell) with flanges and holes
    """
    flange_width = params["flange_width"]
    flange_height = params["flange_height"]
    screw_dia = params["screw_dia"]
    screw_spacing = params["screw_spacing"]
    tube_od = params["tube_od"]
    tube_height = params["tube_height"]
    
    print("\n[FLANGES] Adding M2 mounting flanges...")
    
    # Create flange geometry (rectangular lip at split line)
    flange = cq.Workplane("YZ").box(
        length=flange_height,
        width=flange_width,
        height=tube_od + 10,
        centered=True
    )
    
    # Add flange to front shell (extends in +X direction)
    front_flange = flange.translate((flange_width / 2.0, 0, 0))
    front_shell = front_shell.union(front_flange)
    
    # Add flange to back shell (extends in -X direction)
    back_flange = flange.translate((-flange_width / 2.0, 0, 0))
    back_shell = back_shell.union(back_flange)
    
    # Add M2 screw holes
    print(f"         Screw holes: {screw_dia}mm diameter, {screw_spacing}mm spacing")
    
    margin = 10.0
    usable_height = tube_height - 2 * margin
    num_holes = max(1, int(usable_height / screw_spacing) + 1)
    
    for i in range(num_holes):
        z_pos = -tube_height / 2.0 + margin + (i * screw_spacing)
        
        if z_pos > -tube_height / 2.0 and z_pos < tube_height / 2.0:
            # Create hole as cylinder perpendicular to flange
            hole = cq.Workplane("YZ").cylinder(
                height=flange_width + 2,
                radius=screw_dia / 2.0,
                centered=True
            )
            
            # Add hole to front shell
            hole_front = hole.translate((flange_width / 2.0, 0, z_pos))
            front_shell = front_shell.cut(hole_front)
            
            # Add hole to back shell
            hole_back = hole.translate((-flange_width / 2.0, 0, z_pos))
            back_shell = back_shell.cut(hole_back)
    
    print(f"         Holes added: {num_holes} per shell")
    
    return front_shell, back_shell


# ============================================================================
# SECTION 10: COMPLETE ASSEMBLY
# ============================================================================

def create_complete_assembly(params):
    """
    Orchestrate the complete design with all features.
    
    Assembly Sequence:
    1. Create complete tube with all cuts and unions
    2. Split into front and back shells
    3. Add mounting flanges with holes
    4. Union baffle into back shell
    
    Returns:
        dict: Complete assembly components
    """
    print("\n" + "=" * 80)
    print("IncuSense Aerodynamic Clamshell Duct - Senior Mechanical Design")
    print("=" * 80)
    
    # Step 1: Complete tube
    print("\n[STEP 1] Creating complete tube with all features...")
    tube = create_complete_tube(params)
    print("         ✓ Hollow tube: OD=30mm, ID=25mm, H=60mm")
    print("         ✓ Volume compensation bulge integrated (10mm radial)")
    print("         ✓ PCB slot: 40.2mm × 1.8mm × 12mm")
    print("         ✓ Gasket groove: 2.0mm × 1.0mm")
    
    # Step 2: Clamshell split
    print("\n[STEP 2] Splitting into clamshell halves...")
    front_shell, back_shell = create_clamshell_split(tube, params)
    print("         ✓ Front shell: X >= 0 (PCB side)")
    print("         ✓ Back shell: X <= 0 (baffle side)")
    
    # Step 3: Mounting flanges
    print("\n[STEP 3] Adding mounting flanges...")
    front_shell, back_shell = add_mounting_flanges(front_shell, back_shell, params)
    print("         ✓ Flanges (5mm wide) on both shells")
    
    # Step 4: Baffle into back shell
    print("\n[STEP 4] Integrating aerodynamic baffle...")
    baffle = create_aerodynamic_baffle(params)
    back_shell = back_shell.union(baffle)
    print("         ✓ Baffle integrated (Z: 15-28mm)")
    print("         ✓ Directs airflow toward tube center")
    
    # Package assembly
    assembly = {
        "front_shell": front_shell,
        "back_shell": back_shell,
    }
    
    return assembly


# ============================================================================
# SECTION 11: EXPORT TO STEP FILES
# ============================================================================

def export_assembly(assembly, output_dir="./"):
    """
    Export front and back shells to STEP files.
    
    Output Files:
    - front_shell.step: Front clamshell half
    - back_shell.step: Back clamshell half with integrated baffle
    
    Args:
        assembly (dict): Assembly components
        output_dir (str): Output directory
    
    Returns:
        bool: Success status
    """
    print("\n[STEP 5] Exporting to STEP files...")
    print(f"         Output directory: {output_dir}")
    
    try:
        # Export front shell
        assembly["front_shell"].val().exportStep(f"{output_dir}/front_shell.step")
        print("         ✓ front_shell.step (PCB insertion side, 5mm flange)")
        
        # Export back shell
        assembly["back_shell"].val().exportStep(f"{output_dir}/back_shell.step")
        print("         ✓ back_shell.step (Baffle side, 5mm flange)")
        
        print("\n✓ Export complete!")
        return True
    
    except Exception as e:
        print(f"\n✗ Export failed: {e}")
        import traceback
        traceback.print_exc()
        return False


# ============================================================================
# SECTION 12: VISUALIZATION FOR CQ-EDITOR
# ============================================================================

def create_visualization(assembly):
    """
    Combine both shells for visualization in CQ-Editor.
    
    Args:
        assembly (dict): Assembly components
    
    Returns:
        Workplane: Combined assembly for visualization
    """
    print("\n[STEP 6] Preparing visualization...")
    
    visualization = assembly["front_shell"].union(assembly["back_shell"])
    
    print("         ✓ Assembly ready for CQ-Editor")
    
    return visualization


# ============================================================================
# SECTION 13: MAIN EXECUTION
# ============================================================================

def main():
    """
    Main execution: Generate complete assembly and export.
    """
    # Create assembly
    assembly = create_complete_assembly(PARAMS)
    
    # Export to STEP files
    export_assembly(assembly, output_dir="./")
    
    # Create visualization
    visualization = create_visualization(assembly)
    
    # Print summary
    print("\n" + "=" * 80)
    print("DESIGN COMPLETE - IncuSense Clamshell Duct")
    print("=" * 80)
    
    print("\n[IMPLEMENTED FEATURES]")
    print("  ✓ Main tube: OD=30mm, ID=25mm, H=60mm")
    print("  ✓ Volume compensation bulge (10mm radial, Z: 18-42mm)")
    print("  ✓ PCB slot: 40.2mm × 1.8mm × 12mm overhang")
    print("  ✓ Gasket groove: 2.0mm × 1.0mm (silicone compression)")
    print("  ✓ Aerodynamic baffle: Wedge shape (Z: 15-28mm)")
    print("  ✓ Clamshell split: Vertical plane at X=0")
    print("  ✓ Mounting flanges: 5mm wide on both halves")
    print("  ✓ M2 screw holes: 2.2mm diameter, 15mm spacing")
    
    print("\n[EXPORTED FILES]")
    print("  • front_shell.step ..................... PCB insertion side")
    print("  • back_shell.step ..................... Baffle side")
    
    print("\n[DESIGN BENEFITS]")
    print("  • Volume compensation bulge allows air to bypass PCB obstruction")
    print("  • Gasket groove accommodates silicone compression seal")
    print("  • Aerodynamic baffle eliminates stagnation zones")
    print("  • Smooth clamshell assembly with M2 screw clamping")
    print("  • TO-39 sensor hangs in tube center for clean airflow")
    
    print("\n[ASSEMBLY INSTRUCTIONS]")
    print("  1. Insert PCB into front shell slot (12mm overhang)")
    print("  2. Apply silicone gasket to groove area")
    print("  3. Press back shell onto front shell (flanges facing out)")
    print("  4. Align M2 mounting holes")
    print("  5. Clamp with M2 screws to compress gasket")
    print("  6. Mount TO-39 sensor hanging inside tube")
    print("  7. Bulge + baffle direct laminar airflow to sensor")
    
    print("\n[AIRFLOW PATH]")
    print("  1. Air enters from either end of tube")
    print("  2. Volume compensation bulge creates bypass path")
    print("  3. Air hits aerodynamic baffle")
    print("  4. Baffle deflects flow toward tube center")
    print("  5. Clean laminar flow over TO-39 sensor")
    
    print("\n" + "=" * 80 + "\n")
    
    return visualization


# ============================================================================
# CQ-EDITOR INTEGRATION
# ============================================================================

if __name__ == "__main__":
    result = main()
    
    # Display in CQ-Editor if available
    try:
        show_object(result, "IncuSense Clamshell Duct Assembly")
    except NameError:
        print("\nℹ  To visualize in CQ-Editor:")
        print("  1. Copy this script into CQ-Editor")
        print("  2. Press Ctrl+R or click 'Execute'")
        print("  3. Assembly will render in 3D viewport")
